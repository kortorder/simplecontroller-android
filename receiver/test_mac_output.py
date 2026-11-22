import contextlib
from copy import deepcopy
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
import uuid
from unittest.mock import Mock,patch
import mac_output as mac
from mac_recording_peer import RecordingOutput
from ios_pc_protocol import neutral_output
import start_mac_receiver
from configure_mac_receiver import save_mapping

class MappingTests(unittest.TestCase):
    def test_default_fields_and_all_physical_letters_digits(self):
        self.assertEqual(mac.validate_mapping(mac.DEFAULT_MAPPING),mac.DEFAULT_MAPPING)
        self.assertFalse(set('ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789')-set(mac.KEY_CODES))
        self.assertEqual([mac.KEY_CODES[k] for k in 'QWERTY'],[12,13,14,15,17,16])
        self.assertEqual(mac.KEY_CODES['P'],35)
    def test_aliases_command_option_and_named_keys(self):
        for text,expected in [('COMMAND','LWIN'),('OPTION','LALT'),('space bar','SPACE'),(' ','SPACE'),('right control','RCTRL'),('comma',',')]:
            self.assertEqual(mac.canonical_key(text),expected)
    def test_unsupported_key_does_not_become_arbitrary_native_code(self):
        for value in ['','F21','CMD+Q',True,42,'__import__']:
            with self.assertRaises(ValueError):mac.canonical_key(value)
    def test_invalid_schema_boolean_numbers_and_nan_fail_closed(self):
        for field,value in [('version',True),('version',2),('stick_deadzone',float('nan')),('trigger_threshold',True),('right_stick_pixels_per_second',5000),('invert_right_stick_y',1)]:
            candidate=deepcopy(mac.DEFAULT_MAPPING);candidate[field]=value
            with self.subTest(field=field,value=value),self.assertRaises(ValueError):mac.validate_mapping(candidate)
    def test_missing_unknown_mapping_fields_and_invalid_targets_rejected(self):
        for section in ['buttons','left_stick','triggers']:
            candidate=deepcopy(mac.DEFAULT_MAPPING);candidate[section]['extra']='A'
            with self.assertRaises(ValueError):mac.validate_mapping(candidate)
        candidate=deepcopy(mac.DEFAULT_MAPPING);candidate['buttons']['A']='BOGUS'
        with self.assertRaises(ValueError):mac.validate_mapping(candidate)
    def test_off_mapping_is_explicit_and_supported(self):
        candidate=deepcopy(mac.DEFAULT_MAPPING);candidate['buttons']['A']='OFF'
        self.assertEqual(mac.validate_mapping(candidate)['buttons']['A'],'OFF')
    def test_save_is_validated_and_keeps_previous_mapping(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'Mac-Mapping.json';save_mapping(path,mac.DEFAULT_MAPPING)
            changed=deepcopy(mac.DEFAULT_MAPPING);changed['buttons']['A']='F'
            save_mapping(path,changed)
            self.assertEqual(mac.load_mapping(path),changed)
            self.assertEqual(mac.load_mapping(path.with_name('Mac-Mapping.previous.json')),mac.DEFAULT_MAPPING)
            changed['buttons']['A']='BOGUS'
            with self.assertRaises(ValueError):save_mapping(path,changed)
            self.assertEqual(mac.load_mapping(path)['buttons']['A'],'F')
    def test_duplicate_and_oversized_json_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'mapping.json';path.write_text('{"version":1,"version":1}')
            with self.assertRaises(ValueError):mac.load_mapping(path)
            path.write_text(' '*17000)
            with self.assertRaises(ValueError):mac.load_mapping(path)

class RuntimeTests(unittest.TestCase):
    def setUp(self):
        self.now=100.;self.events=[]
        self.runtime=mac.configure(RecordingOutput(self.events.append),clock=lambda:self.now)
        self.pad=mac.MappedGamepad()
    def tearDown(self):self.runtime.close()
    def key_events(self):return [(e['key'],e['down']) for e in self.events if e['kind']=='key']
    def test_button_and_direct_key_have_independent_ownership(self):
        self.pad.press_button('A');self.pad.update();mac.keyboard.press('space')
        self.pad.release_button('A');self.pad.update();self.assertEqual(self.key_events(),[('SPACE',True)])
        mac.keyboard.release('space');self.assertEqual(self.key_events(),[('SPACE',True),('SPACE',False)])
    def test_trigger_and_mouse_hold_share_without_premature_release(self):
        self.pad.right_trigger_float(.1);self.pad.update();mac.mouse.mouseDown('left')
        self.pad.right_trigger_float(0);self.pad.update();self.assertEqual(len(self.events),1)
        mac.mouse.mouseUp('left');self.assertEqual([(e['button'],e['down']) for e in self.events],[('LEFT',True),('LEFT',False)])
    def test_left_stick_is_wasd_with_diagonals_and_reversal(self):
        self.pad.left_joystick_float(.8,.9);self.pad.update()
        self.assertEqual(self.runtime.applied,{'D','W'})
        self.pad.left_joystick_float(-.8,-.9);self.pad.update()
        self.assertEqual(self.runtime.applied,{'A','S'})
        self.assertEqual(self.key_events()[:4],[('D',True),('W',True),('D',False),('W',False)])
    def test_deadzone_and_trigger_threshold_edges(self):
        self.pad.left_joystick_float(.2,-.2);self.pad.left_trigger_float(0);self.pad.update();self.assertEqual(self.events,[])
        self.pad.left_trigger_float(.001);self.pad.update();self.assertIn('MOUSE_RIGHT',self.runtime.applied)
    def test_custom_mapping_and_off_do_not_edit_phone_profiles(self):
        self.runtime.mapping['buttons']['A']='OFF';self.runtime.mapping['triggers']['LT']='LSHIFT'
        self.pad.press_button('A');self.pad.left_trigger_float(.5);self.pad.update()
        self.assertEqual(self.runtime.applied,{'LSHIFT'})
    def test_right_stick_continues_without_new_packet_and_centers(self):
        self.pad.right_joystick_float(1,0);self.pad.update()
        self.now+=.02;self.runtime.tick();self.now+=.02;self.runtime.tick()
        self.assertEqual(len(self.events),2);self.assertTrue(all(e['dx']>0 and e['dy']==0 for e in self.events))
        self.pad.right_joystick_float(0,0);self.pad.update();self.now+=.02;self.runtime.tick();self.assertEqual(len(self.events),2)
    def test_camera_y_up_is_negative_pointer_y_and_can_invert(self):
        self.pad.right_joystick_float(0,1);self.pad.update();self.now+=.02;self.runtime.tick()
        self.assertLess(self.events[-1]['dy'],0)
        self.runtime.mapping['invert_right_stick_y']=True;self.now+=.02;self.runtime.tick();self.assertGreater(self.events[-1]['dy'],0)
    def test_long_scheduler_pause_does_not_jump_and_diagonal_speed_is_bounded(self):
        self.pad.right_joystick_float(1,1);self.pad.update();self.now+=30;self.runtime.tick()
        e=self.events[-1];self.assertLessEqual((e['dx']**2+e['dy']**2)**.5,45.1)
    def test_small_right_stick_is_ignored_and_remainder_cleared(self):
        self.pad.right_joystick_float(.1,.1);self.pad.update();self.now+=.02;self.runtime.tick()
        self.assertEqual(self.events,[]);self.assertEqual(self.runtime.remainder,[0.,0.])
    def test_modifier_chords_press_first_and_release_last(self):
        self.runtime.set_source('chord',{'A','LCTRL','MOUSE_LEFT'})
        self.assertEqual(self.events[0],{'kind':'key','key':'LCTRL','down':True})
        self.runtime.set_source('chord',set());self.assertEqual(self.events[-1],{'kind':'key','key':'LCTRL','down':False})
    def test_snapshot_batch_modifier_before_mapped_button_and_pointer(self):
        with self.runtime.batch():
            self.pad.press_button('A');self.pad.update()
            mac.keyboard.press('lctrl');mac.mouse.mouseDown('left');mac.send_relative_mouse(5,7)
            self.assertEqual(self.events,[])
        self.assertEqual(self.events[0]['key'],'LCTRL');self.assertEqual(self.events[-1],{'kind':'move','dx':5,'dy':7})
    def test_fractional_pointer_and_scroll_accumulate_without_losing_small_moves(self):
        for _ in range(4):self.runtime.move(.3,-.3);self.runtime.wheel(.3)
        self.assertEqual(self.events,[{'kind':'move','dx':1,'dy':-1},{'kind':'wheel','ticks':1}])
    def test_invalid_pointer_or_scroll_is_rejected_before_native_write(self):
        for value in (float('nan'),float('inf'),5000):
            with self.assertRaises(ValueError):self.runtime.move(value,0)
            with self.assertRaises(ValueError):self.runtime.wheel(value)
        self.assertEqual(self.events,[])
    def test_release_clears_fractional_motion_before_next_session(self):
        self.runtime.move(.8,.8);self.runtime.wheel(.8);self.runtime.release_all()
        self.runtime.move(.3,.3);self.runtime.wheel(.3);self.assertEqual(self.events,[])
    def test_multiple_players_share_desktop_without_releasing_other_hold(self):
        other=mac.MappedGamepad();self.pad.press_button('A');self.pad.update();other.press_button('A');other.update()
        self.pad.reset();self.pad.update();self.assertEqual(self.runtime.applied,{'SPACE'})
        other.reset();other.update();self.assertEqual(self.runtime.applied,set())
    def test_release_all_stops_both_sticks_clicks_and_keys(self):
        self.pad.press_button('B');self.pad.left_trigger_float(1);self.pad.right_joystick_float(1,1);self.pad.update();mac.keyboard.press('w')
        self.runtime.release_all();self.assertEqual(self.runtime.applied,set());self.assertEqual(self.pad.right,(0,0))
        count=len(self.events);self.now+=.02;self.runtime.tick();self.assertEqual(len(self.events),count)
    def test_partial_native_failure_attempts_release_and_blocks_new_input(self):
        output=self.runtime.output;original=output.key
        def fail(key,down):
            original(key,down)
            if key=='A' and down:raise OSError('write failed')
        output.key=fail
        with self.assertRaises(OSError):self.runtime.set_source('test',{'A','LSHIFT'})
        self.assertEqual(self.runtime.applied,set());self.assertTrue(self.runtime.stop.is_set())
        with self.assertRaises(OSError):mac.keyboard.press('w')

class NativeBindingsTests(unittest.TestCase):
    def setUp(self):
        self.cg=SimpleLibrary();self.cf=SimpleLibrary();self.ax=SimpleLibrary()
        self.ax.AXIsProcessTrusted.return_value=True
        self.cg.CGEventCreate.return_value=100;self.cg.CGEventCreateKeyboardEvent.return_value=101
        self.cg.CGEventCreateMouseEvent.return_value=102;self.cg.CGEventCreateScrollWheelEvent.return_value=103
        self.cg.CGEventGetLocation.return_value=mac.CGPoint(50,100)
        self.output=mac.QuartzOutput(self.cg,self.cf,self.ax)
    def test_keycode_flags_and_event_release(self):
        self.output.key('LCTRL',True);self.output.key('A',True)
        self.cg.CGEventCreateKeyboardEvent.assert_called_with(None,0,True)
        self.cg.CGEventSetFlags.assert_called_with(101,1<<18)
        self.cf.CFRelease.assert_called_with(101)
    def test_mouse_drag_uses_drag_event_and_relative_fields(self):
        self.output.mouse('RIGHT',True);self.output.move(12,-3)
        args=self.cg.CGEventCreateMouseEvent.call_args.args
        self.assertEqual(args[1],7);self.assertEqual((args[2].x,args[2].y),(62,97));self.assertEqual(args[3],1)
        self.assertEqual([c.args[1:] for c in self.cg.CGEventSetIntegerValueField.call_args_list],[(4,12),(5,-3)])
    def test_scroll_uses_signed_32_bit_wheel(self):
        self.output.wheel(-2);args=self.cg.CGEventCreateScrollWheelEvent.call_args.args
        self.assertEqual(args[:3],(None,1,1));self.assertEqual(args[3].value,-2)
    def test_permission_removed_never_posts_but_releases_created_event(self):
        self.ax.AXIsProcessTrusted.return_value=False
        with self.assertRaises(PermissionError):self.output.key('A',True)
        self.cg.CGEventPost.assert_not_called();self.cf.CFRelease.assert_called_with(101)
    def test_event_creation_failure_is_visible(self):
        self.cg.CGEventCreateKeyboardEvent.return_value=None
        with self.assertRaises(OSError):self.output.key('A',True)
        self.cg.CGEventPost.assert_not_called()

class SimpleLibrary:
    def __getattr__(self,name):
        result=Mock();setattr(self,name,result);return result

class LauncherTests(unittest.TestCase):
    def test_platform_version_architecture_gates(self):
        for system,machine,version,osversion in [('win32','AMD64',(3,12),'13.0'),('darwin','arm64',(3,9),'13.0'),('darwin','i386',(3,12),'13.0'),('darwin','arm64',(3,12),'12.0')]:
            with patch.object(start_mac_receiver.sys,'platform',system),patch.object(start_mac_receiver.sys,'version_info',version),patch.object(start_mac_receiver.platform,'machine',return_value=machine),patch.object(start_mac_receiver.platform,'mac_ver',return_value=(osversion,(),'')):
                self.assertIsNotNone(start_mac_receiver.platform_problem())
    def test_permission_denied_explains_steps_before_receiver_import(self):
        with patch.object(start_mac_receiver,'platform_problem',return_value=None),patch.object(start_mac_receiver,'QuartzOutput') as output,patch.object(start_mac_receiver.runpy,'run_path') as run,contextlib.redirect_stdout(io.StringIO()) as messages:
            output.return_value.trusted.return_value=False
            self.assertEqual(start_mac_receiver.main([]),2);run.assert_not_called()
            self.assertIn('Accessibility',messages.getvalue());self.assertIn('Terminal',messages.getvalue())
    def test_check_does_not_start_output_or_receiver(self):
        with patch.object(start_mac_receiver,'platform_problem',return_value=None),patch.object(start_mac_receiver,'QuartzOutput') as output,patch.object(start_mac_receiver.runpy,'run_path') as run,patch.object(start_mac_receiver,'configure') as configure,contextlib.redirect_stdout(io.StringIO()):
            output.return_value.trusted.return_value=True
            self.assertEqual(start_mac_receiver.main(['--check']),0);run.assert_not_called();configure.assert_not_called()

class ProductionMacReceiverTests(unittest.TestCase):
    def setUp(self):
        self.events=[];self.now=100.
        self.runtime=mac.configure(RecordingOutput(self.events.append),clock=lambda:self.now)
        path=Path(__file__).with_name('simple_controller_receiver.py')
        spec=importlib.util.spec_from_file_location('mac_test_receiver_'+uuid.uuid4().hex,path);self.receiver=importlib.util.module_from_spec(spec)
        with patch.dict(os.environ,{'SIMPLE_CONTROLLER_BACKEND':'mac','LOCALAPPDATA':tempfile.gettempdir()}):spec.loader.exec_module(self.receiver)
        self.receiver.ios_pc_adapter.protocol.clock=lambda:self.now
        self.receiver.phone_output_protocol.clock=lambda:self.now
        self.address=('127.0.0.1',39001);self.session=str(uuid.uuid4())
    def tearDown(self):self.receiver.release_outputs(tuple(self.receiver.gamepads),'Mac test');self.runtime.close()
    def send(self,data):return self.receiver.process_command(data,self.address)
    def ios(self,kind,seq,output=None):
        packet={'protocolVersion':'SCPC1','kind':kind,'session':self.session,'sequence':seq}
        if kind!='hello':packet['token']=self.token
        if output is not None:packet['output']=output
        reply=self.send(json.dumps(packet));return json.loads(reply) if reply else None
    def connect_ios(self):
        self.token=self.ios('hello',0)['token'];self.ios('state',1,neutral_output())
    def test_mac_connection_banner_shows_ip_port_without_windows_firewall(self):
        with patch.object(self.receiver,'get_lan_ip_candidates',return_value=['192.168.1.2']),contextlib.redirect_stdout(io.StringIO()) as output:
            self.receiver.show_connection_info()
        self.assertIn('192.168.1.2',output.getvalue());self.assertIn('42734',output.getvalue())
        self.assertIn('macOS',output.getvalue());self.assertNotIn('Windows Firewall',output.getvalue())
    def test_ios_production_protocol_maps_full_snapshot_and_expires(self):
        self.connect_ios();o=neutral_output();o['gamepad']['leftStick']={'x':1,'y':1};o['gamepad']['rightStick']={'x':1,'y':0}
        o['gamepad']['buttons']=['a'];o.update(leftTrigger=.5,rightTrigger=.7,keys=['LCTRL'],pointer=[8,-4],scroll=40)
        self.assertEqual(self.ios('state',2,o)['kind'],'ack')
        self.assertEqual(self.runtime.applied,{'W','D','SPACE','LCTRL','MOUSE_LEFT','MOUSE_RIGHT'})
        self.assertEqual(self.events[0]['key'],'LCTRL')
        self.assertIn({'kind':'move','dx':8,'dy':-4},self.events);self.assertIn({'kind':'wheel','ticks':1},self.events)
        self.now+=.02;self.runtime.tick();self.assertGreater(self.events[-1]['dx'],0)
        self.now+=.5;self.receiver.ios_pc_adapter.protocol.expire();self.assertEqual(self.runtime.applied,set())
        count=len(self.events);self.now+=.02;self.runtime.tick();self.assertEqual(count,len(self.events))
    def test_ios_duplicates_do_not_duplicate_pointer_or_clicks(self):
        self.connect_ios();o=neutral_output();o['pointer']=[10,10];o['mouse']=['LEFT'];self.ios('state',2,o)
        events=list(self.events);self.ios('state',2,o);self.assertEqual(events,self.events)
    def test_ios_stop_does_not_resume_old_held_output(self):
        self.connect_ios();o=neutral_output();o['rightTrigger']=1;self.ios('state',2,o);self.ios('stop',3)
        self.assertEqual(self.runtime.applied,set());self.assertIsNone(self.ios('state',4,o))
    def android(self):
        self.assertEqual(self.send('CONNECT:player1'),'CONNECTED:player1')
        self.assertIn('PHONE_OUTPUT_V1',self.send('PHONE_CAPS_V1:android:nonce'))
        self.assertEqual(self.send('PHONE_ACTIVATE_V1:android:1'),'PHONE_ACK_V1:android:1')
        self.send('PHONE_LEASE_V1:android:2:3000')
    def test_android_production_negotiation_mapping_and_lease_expiry(self):
        self.android();self.send('OUTPUT:android:3:X360A_HOLD');self.send('STICK_L:android:4:0,-1')
        self.send('OUTPUT:android:5:LT:0.5');self.assertEqual(self.runtime.applied,{'SPACE','W','MOUSE_RIGHT'})
        self.now+=3.;self.receiver.phone_output_protocol.expire_leases();self.assertEqual(self.runtime.applied,set())
        self.send('OUTPUT:android:6:X360B_HOLD');self.assertEqual(self.runtime.applied,set())
    def test_android_release_all_and_reactivation_blocks_stale_commands(self):
        self.android();self.send('OUTPUT:android:3:X360A_HOLD')
        self.assertEqual(self.send('RELEASE_ALL:android:4'),'RELEASE_ALL_ACK:android:4')
        self.assertEqual(self.runtime.applied,set());self.send('PHONE_ACTIVATE_V1:android:5')
        self.send('PHONE_LEASE_V1:android:6:3000');self.send('OUTPUT:android:7:X360B_HOLD')
        self.send('RELEASE_ALL:android:4');self.assertEqual(self.runtime.applied,{'LCTRL'})
    def test_android_scroll_is_mac_lines_not_windows_120_multiplier(self):
        self.android();self.send('OUTPUT:android:3:SCROLL:2')
        self.assertEqual([e for e in self.events if e['kind']=='wheel'],[{'kind':'wheel','ticks':2}])
        for seq in range(4,8):self.send(f'OUTPUT:android:{seq}:SCROLL:0.3')
        self.assertEqual([e['ticks'] for e in self.events if e['kind']=='wheel'],[2,1])
    def test_android_small_mouse_delta_accumulates(self):
        self.android()
        for seq in range(3,7):self.send(f'OUTPUT:android:{seq}:DELTA:0.008,0.008')
        self.assertEqual([e for e in self.events if e['kind']=='move'],[{'kind':'move','dx':1,'dy':1}])
    def test_snapshot_takes_receiver_lock_before_mac_runtime_lock(self):
        original=self.runtime.batch
        @contextlib.contextmanager
        def checked():
            self.assertTrue(self.receiver.release_state_lock._is_owned())
            with original():yield
        with patch.object(self.runtime,'batch',checked):self.connect_ios()
    def test_android_phone_aggregate_key_and_trigger_events_preserved(self):
        self.android();self.send('PHONE_STATE_V1:android:3:GLOBAL%7CKEY%7CW:KEY_DOWN%3AW')
        self.send('PHONE_STATE_V1:android:4:player1%7CTRIGGER%7CRT:RT%3A0.5')
        self.assertEqual(self.runtime.applied,{'W','MOUSE_LEFT'})
        self.send('PHONE_STATE_V1:android:5:GLOBAL%7CKEY%7CW:KEY_UP%3AW')
        self.assertEqual(self.runtime.applied,{'MOUSE_LEFT'})

if __name__=='__main__':unittest.main()
