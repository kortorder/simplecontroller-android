import copy
import json
import threading
import unittest
from ios_pc_protocol import IOSPCProtocol, neutral_output
from ios_pc_adapter import IOSPCAdapter, commands
from phone_output_protocol import PhoneOutputProtocolState

SESSION = '3D4670B1-2292-48DF-8804-D96D76736510'
OTHER = 'FE2E3DD3-1860-4978-9A19-41E40A596128'
ADDR = ('192.0.2.1',1234)

class ProtocolTests(unittest.TestCase):
    def setUp(self):
        self.now = 0.
        self.output = []
        self.releases = []
        self.activated = []
        self.p = IOSPCProtocol(self.activated.append,
            lambda out,old,session,seq:self.output.append(copy.deepcopy(out)), self.releases.append,
            clock=lambda:self.now)
        self.token = self.hello()

    def message(self,kind,seq=0,session=SESSION,output=None,token=None,addr=ADDR):
        p = dict(protocolVersion='SCPC1',kind=kind,session=session,sequence=seq)
        if kind != 'hello':p['token'] = token or self.token
        if output is not None:p['output'] = output
        handled,response = self.p.process(json.dumps(p),addr)
        self.assertTrue(handled)
        return json.loads(response) if response else None

    def hello(self,session=SESSION):
        reply = self.message('hello',session=session)
        self.assertEqual(reply['kind'],'ready')
        return reply['token']

    def arm(self):
        self.assertEqual(self.message('state',1,output=neutral_output())['kind'],'ack')
        self.assertEqual(self.activated,[SESSION])

    def test_offer_does_not_activate_or_renew_deadline(self):
        self.assertEqual(self.activated,[])
        self.now = 2.9;self.hello()
        self.now = 3.;self.p.expire()
        self.assertIsNone(self.message('state',1,output=neutral_output()))

    def test_non_neutral_start_is_refused(self):
        out = neutral_output();out['gamepad']['buttons']=['a']
        self.assertIsNone(self.message('state',1,output=out))
        self.assertEqual(self.output,[])
        self.arm()

    def test_order_duplicates_pointer_and_release(self):
        self.arm()
        out = neutral_output();out['keys']=['W'];out['pointer']=[5.25,-3.5]
        self.message('state',2,output=out)
        self.message('state',2,output=out)
        self.message('state',1,output=neutral_output())
        self.assertEqual(len(self.output),2)
        self.assertEqual(self.output[-1],out)
        self.message('stop',3)
        self.assertEqual(self.releases,[SESSION])
        self.assertIsNone(self.message('state',4,output=out))
        self.assertIsNone(self.message('hello'))

    def test_exact_lease_and_duplicate_do_not_renew(self):
        self.arm();self.now=.49
        self.message('state',1,output=neutral_output())
        self.now=.5;self.p.expire()
        self.assertEqual(self.releases,[SESSION])
        self.assertIsNone(self.message('state',2,output=neutral_output()))

    def test_new_heartbeat_renews_but_stale_traffic_does_not(self):
        self.arm();self.now=.3;self.message('state',2,output=neutral_output())
        self.now=.79;self.p.expire();self.assertEqual(self.releases,[])
        self.now=.8;self.p.expire();self.assertEqual(self.releases,[SESSION])

    def test_wrong_peer_and_challenge_do_not_activate(self):
        self.assertIsNone(self.message('state',1,output=neutral_output(),addr=('192.0.2.2',1234)))
        self.assertIsNone(self.message('state',1,output=neutral_output(),token=OTHER))
        self.assertEqual(self.activated,[])

    def test_replacement_neutralizes_old_and_rejects_old_frames(self):
        self.arm();token = self.hello(OTHER)
        self.message('state',1,session=OTHER,token=token,output=neutral_output())
        self.assertEqual(self.releases,[SESSION]);self.assertEqual(self.activated,[SESSION,OTHER])
        self.assertIsNone(self.message('state',2,output=neutral_output()))

    def test_malformed_snapshot_has_no_outputs(self):
        self.arm()
        corruptions = [lambda x:x.update(leftTrigger=True),lambda x:x.update(rightTrigger=1.1),
            lambda x:x['gamepad'].update(dpad=[]),lambda x:x['gamepad'].update(buttons=['a','a']),
            lambda x:x.update(keys=['KEY_DOWN:EVIL']),lambda x:x.update(mouse=['LEFT','LEFT']),
            lambda x:x.update(pointer=[1e309,0]),lambda x:x.update(scroll=10**500),
            lambda x:x.update(extra=1),lambda x:x['gamepad']['leftStick'].update(x=-1.01)]
        for corrupt in corruptions:
            out=neutral_output();corrupt(out)
            self.assertIsNone(self.message('state',2,output=out))
        self.assertEqual(len(self.output),1)
        self.assertEqual(self.p.process('{"protocolVersion":"SCPC1","protocolVersion":"SCPC1"}',ADDR),(True,None))

    def test_large_pointer_jump_fails_closed(self):
        self.arm();out=neutral_output();out['pointer']=[4097,0]
        self.assertEqual(self.message('state',2,output=out)['kind'],'error')
        self.assertEqual(self.releases,[SESSION]);self.assertEqual(len(self.output),1)

    def test_invalid_sequence_and_unrelated_android_commands(self):
        for seq in [True,-1,1.5,9007199254740992]:
            self.assertIsNone(self.message('state',seq,output=neutral_output()))
        for value in ['PING','player1:X360A','PHONE_CAPS_V1:test:nonce','SIMPLE_CONTROLLER_DISCOVER']:
            self.assertEqual(self.p.process(value,ADDR),(False,None))

    def test_apply_failure_returns_error_and_releases(self):
        self.arm()
        def fail(*args):raise OSError('Fake native error')
        self.p.apply=fail
        self.assertEqual(self.message('state',2,output=neutral_output())['kind'],'error')
        self.assertEqual(self.releases,[SESSION])

    def test_hello_flood_bounded(self):
        import uuid
        for _ in range(100):
            self.p.process(json.dumps(dict(protocolVersion='SCPC1',kind='hello',session=str(uuid.uuid4()),sequence=0)),ADDR)
        self.assertEqual(len(self.p.offers),32)

class AdapterTests(unittest.TestCase):
    def setUp(self):
        self.writes=[];self.neutrals=[];self.moves=[];self.scrolls=[]
        self.lock=threading.RLock()
        self.phone=PhoneOutputProtocolState(self.lock,lambda s,n:True,
            lambda cmd,player:self.writes.append(cmd),lambda *args:None,activate_phone=self.neutrals.append)
        self.a=IOSPCAdapter(self.phone,self.neutrals.append,lambda x,y:self.moves.append((x,y)),self.scrolls.append,self.lock)
        self.a.activate(SESSION);self.writes.clear()

    def test_full_mapping_and_release_preserves_fractional_pointer(self):
        before=neutral_output();out=neutral_output()
        out.update(leftTrigger=.5,rightTrigger=.7,keys=['LCTRL','W'],mouse=['LEFT'],pointer=[1.5,-2.25],scroll=60)
        out['gamepad'].update(buttons=['a','menu'],dpad='upRight',rightStick={'x':.6,'y':-.4})
        self.a.apply(out,before,SESSION,1)
        for value in ['X360A_HOLD','X360START_HOLD','X360UP_HOLD','X360RIGHT_HOLD','LT:0.5','RT:0.7','KEY_DOWN:LCTRL','MOUSE_LEFT_DOWN','STICK_R:0.6,0.4']:
            self.assertIn(value,self.writes)
        self.assertEqual(self.moves,[(1,-2)]);self.assertEqual(self.scrolls,[1])
        newer=copy.deepcopy(out);newer['pointer']=[2,-3];newer['scroll']=80;newer['keys']=[];newer['mouse']=[]
        self.a.apply(newer,out,SESSION,2)
        self.assertEqual(self.moves[-1],(1,-1));self.assertEqual(self.scrolls,[1,1])
        self.assertIn('KEY_UP:LCTRL',self.writes);self.assertIn('MOUSE_LEFT_UP',self.writes)

    def test_unchanged_heartbeat_does_not_repeat_actions(self):
        out=neutral_output();out['keys']=['W']
        self.a.apply(out,neutral_output(),SESSION,1);count=len(self.writes)
        self.a.apply(out,out,SESSION,2)
        self.assertEqual(len(self.writes),count)

    def test_stop_only_releases_current_phone_owner(self):
        self.a.release(SESSION)
        self.assertEqual(self.neutrals,['player1','player1'])
        self.phone.players['player1']=OTHER
        self.a.release(SESSION)
        self.assertEqual(self.neutrals,['player1','player1'])
        with self.assertRaises(RuntimeError):self.a.apply(neutral_output(),neutral_output(),SESSION,2)

if __name__=='__main__':unittest.main()
