import threading
import unittest
from phone_output_protocol import PhoneOutputProtocolState

class PhoneLeaseTests(unittest.TestCase):
    def setUp(self):
        self.time=0.;self.outputs=[];self.neutrals=[]
        self.p=PhoneOutputProtocolState(threading.RLock(),lambda s,n:True,
            lambda command,player:self.outputs.append(command),lambda *args:None,
            activate_phone=self.neutrals.append,clock=lambda:self.time)
        self.p.process('PHONE_CAPS_V1:android:nonce','player1')
        self.p.process('PHONE_ACTIVATE_V1:android:1','player1')
    def send(self,text):return self.p.process(text,'player1')[1]
    def test_negotiated_expiry_releases_and_blocks_every_late_state(self):
        self.assertEqual(self.send('PHONE_LEASE_V1:android:2:3000'),'PHONE_LEASE_V1_ACK:android:2:3000')
        self.send('PHONE_STATE_V1:android:3:player1%7CXBOX%7CA:X360A_HOLD')
        self.time=3.;self.p.expire_leases()
        self.assertEqual(self.neutrals,['player1','player1'])
        count=len(self.outputs)
        for command in ['PHONE_STATE_V1:android:4:player1%7CXBOX%7CA:X360A_HOLD',
                        'PHONE_EVENT_V1:android:5:scope:0:1:X360B',
                        'PHONE_LEASE_V1:android:6:3000','PHONE_ACTIVATE_V1:android:1']:
            self.assertIsNone(self.send(command))
        self.assertEqual(len(self.outputs),count)
        self.send('PHONE_CAPS_V1:android:again')
        self.assertIsNone(self.send('PHONE_STATE_V1:android:7:player1%7CXBOX%7CA:X360A_HOLD'))
        self.assertEqual(self.send('PHONE_ACTIVATE_V1:android:8'),'PHONE_ACK_V1:android:8')
        self.assertEqual(self.send('PHONE_LEASE_V1:android:9:3000'),'PHONE_LEASE_V1_ACK:android:9:3000')
        self.assertIsNone(self.send('PHONE_STATE_V1:android:7:player1%7CXBOX%7CA:X360A_HOLD'))
        self.send('PHONE_STATE_V1:android:10:player1%7CXBOX%7CB:X360B_HOLD')
        self.assertEqual(self.outputs[-1],'X360B_HOLD')
    def test_duplicate_or_invalid_renewal_cannot_extend_deadline(self):
        self.send('PHONE_LEASE_V1:android:2:3000');self.time=2.9
        self.send('PHONE_LEASE_V1:android:2:3000')
        self.assertIsNone(self.send('PHONE_LEASE_V1:android:3:30000'))
        self.time=3.;self.p.expire_leases();self.assertEqual(len(self.neutrals),2)
    def test_fresh_renewal_extends_deadline(self):
        self.send('PHONE_LEASE_V1:android:2:3000');self.time=2.
        self.send('PHONE_LEASE_V1:android:3:3000');self.time=4.99;self.p.expire_leases()
        self.assertEqual(len(self.neutrals),1)
        self.time=5.;self.p.expire_leases();self.assertEqual(len(self.neutrals),2)
    def test_old_unnegotiated_clients_remain_compatible(self):
        self.time=100.;self.p.expire_leases()
        self.assertEqual(len(self.neutrals),1)
        self.assertIsNotNone(self.send('PHONE_STATE_V1:android:2:player1%7CXBOX%7CA:X360A_HOLD'))
    def test_expiry_cannot_release_replacement_phone(self):
        self.send('PHONE_LEASE_V1:android:2:3000')
        self.p.process('PHONE_CAPS_V1:new:nonce','player1')
        self.p.process('PHONE_ACTIVATE_V1:new:1','player1')
        self.time=3.;self.p.expire_leases()
        self.assertEqual(len(self.neutrals),2)
        self.assertEqual(self.p.players['player1'],'new')

if __name__=='__main__':unittest.main()
