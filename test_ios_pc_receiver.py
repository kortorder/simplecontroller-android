import json
import unittest
import uuid
from unittest.mock import patch
from test_receiver_release_all import receiver, fake_keyboard, fake_pyautogui
from ios_pc_protocol import neutral_output

class IOSReceiverIntegrationTests(unittest.TestCase):
    def setUp(self):
        receiver.release_outputs(tuple(receiver.gamepads),'PC integration setup')
        self.time=100.; self.address=('127.0.0.1',31001);self.session=str(uuid.uuid4())
        self.adapter=receiver.ios_pc_adapter; self.adapter.protocol.clock=lambda:self.time
        self.moves=[];self.wheels=[]
        self.adapter.relative_mouse=lambda x,y:self.moves.append((x,y))
        self.adapter.wheel=self.wheels.append
        self.token=self.send('hello',0)['token'];self.send('state',1,neutral_output())
    def tearDown(self):
        receiver.release_outputs(tuple(receiver.gamepads),'PC integration cleanup')
    def send(self,kind,sequence,output=None):
        packet={'protocolVersion':'SCPC1','kind':kind,'session':self.session,'sequence':sequence}
        if kind!='hello':packet['token']=self.token
        if output is not None:packet['output']=output
        reply=receiver.process_command(json.dumps(packet),self.address)
        return json.loads(reply) if reply else None
    def test_real_dispatch_maps_and_expiry_clears_phone_but_preserves_other_owners(self):
        output=neutral_output();output['gamepad']['buttons']=['a','menu'];output['gamepad']['dpad']='upRight'
        output['gamepad']['leftStick']={'x':.4,'y':-.8};output['gamepad']['rightStick']={'x':-.6,'y':.3}
        output.update(leftTrigger=.35,rightTrigger=.75,keys=['W'],mouse=['LEFT'],pointer=[12.,-4.],scroll=40.)
        self.assertEqual(self.send('state',2,output)['kind'],'ack')
        pad=receiver.gamepads['player1']
        self.assertTrue({'A','START','UP','RIGHT'}.issubset(pad.buttons));self.assertEqual(pad.left_stick,(.4,-.8))
        self.assertEqual(pad.right_stick,(-.6,.3));self.assertEqual(pad.left_trigger,.35);self.assertEqual(pad.right_trigger,.75)
        self.assertIn('w',fake_keyboard.down);self.assertIn('left',fake_pyautogui.down)
        self.assertEqual(self.moves,[(12,-4)]);self.assertEqual(self.wheels,[1])
        self.send('state',2,output);self.assertEqual(len(self.moves),1)
        # A separate voice owner survives phone timeout.
        pad.voice_set('B',True);pad.update()
        self.time+=.5;self.adapter.protocol.expire()
        self.assertEqual(pad.buttons,{'B'});self.assertEqual(pad.left_trigger,0);self.assertEqual(pad.right_trigger,0)
        self.assertEqual(pad.left_stick,(0,0));self.assertEqual(pad.right_stick,(0,0))
        self.assertNotIn('w',fake_keyboard.down);self.assertNotIn('left',fake_pyautogui.down)
        self.assertIsNone(self.send('state',3,output));self.assertEqual(pad.buttons,{'B'})
    def test_android_expiry_rejects_ordered_legacy_and_reactivation_is_neutral(self):
        session='android'+uuid.uuid4().hex;command=lambda value:receiver.process_command(value,self.address)
        protocol=receiver.phone_output_protocol;protocol.clock=lambda:self.time
        command('PHONE_CAPS_V1:'+session+':nonce');command('PHONE_ACTIVATE_V1:'+session+':1')
        command('PHONE_LEASE_V1:'+session+':2:3000')
        command('OUTPUT:'+session+':3:X360A_HOLD')
        self.assertIn('A',receiver.gamepads['player1'].buttons)
        self.time+=3.;protocol.expire_leases();self.assertNotIn('A',receiver.gamepads['player1'].buttons)
        command('OUTPUT:'+session+':4:X360A_HOLD')
        command('STICK_R:'+session+':5:1,1')
        self.assertNotIn('A',receiver.gamepads['player1'].buttons);self.assertEqual(receiver.gamepads['player1'].right_stick,(0,0))
        self.assertEqual(command('PHONE_ACTIVATE_V1:'+session+':6'),'PHONE_ACK_V1:'+session+':6')
        self.assertEqual(command('PHONE_LEASE_V1:'+session+':7:3000'),'PHONE_LEASE_V1_ACK:'+session+':7:3000')
        command('OUTPUT:'+session+':4:X360A_HOLD');self.assertNotIn('A',receiver.gamepads['player1'].buttons)
        command('OUTPUT:'+session+':8:X360B_HOLD');self.assertIn('B',receiver.gamepads['player1'].buttons)

if __name__=='__main__':unittest.main()
