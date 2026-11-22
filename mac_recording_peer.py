"""Integration-test peer: production Mac mapping, recording outputs, no native API.

Run only for testing. This deliberately does NOT control the Mac. Ctrl+C or the
bounded duration ends the capture. JSONL events contain test input, not secrets.
"""
import argparse
import json
import os
from pathlib import Path
import socket
import time
from mac_output import configure, load_mapping

class RecordingOutput:
    def __init__(self,emit):self.emit=emit
    def key(self,key,down):self.emit({'kind':'key','key':key,'down':down})
    def mouse(self,button,down):self.emit({'kind':'mouse','button':button,'down':down})
    def move(self,dx,dy):self.emit({'kind':'move','dx':dx,'dy':dy})
    def wheel(self,ticks):self.emit({'kind':'wheel','ticks':ticks})

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--port',type=int,default=42734)
    parser.add_argument('--bind',default='127.0.0.1');parser.add_argument('--duration',type=int,default=300)
    parser.add_argument('--log',type=Path,required=True);args=parser.parse_args()
    with args.log.open('w',encoding='utf-8',buffering=1) as log:
        def emit(event):
            event['time']=time.monotonic();log.write(json.dumps(event,separators=(',',':'))+'\n')
        runtime=configure(RecordingOutput(emit),load_mapping(Path(__file__).with_name('Mac-Mapping.json')))
        os.environ['SIMPLE_CONTROLLER_BACKEND']='mac'
        os.environ['LOCALAPPDATA']=str(args.log.parent)
        import simple_controller_receiver as receiver
        receiver.PORT=args.port;receiver.HOST=args.bind
        receiver.start_camera_follow_worker()
        with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as sock:
            sock.bind((args.bind,args.port));sock.settimeout(.01)
            print('MAC RECORDING PEER READY',args.bind,args.port,flush=True);emit({'kind':'ready'})
            deadline=time.monotonic()+args.duration
            try:
                while time.monotonic()<deadline:
                    try:
                        packet,address=sock.recvfrom(8193)
                        data=packet.decode('utf-8')
                        response=receiver.process_command(data,address)
                        if response:sock.sendto(response.encode('utf-8'),address)
                        emit({'kind':'packet','size':len(packet),'response':bool(response)})
                    except socket.timeout:pass
                    except UnicodeDecodeError:pass
                    receiver.ios_pc_adapter.protocol.expire()
                    receiver.phone_output_protocol.expire_leases()
                    runtime.tick()
            except KeyboardInterrupt:pass
            finally:
                receiver.stop_camera_follow_worker();receiver.release_outputs(tuple(receiver.gamepads),'recording peer stopped')
                runtime.close();emit({'kind':'closed','held':sorted(runtime.applied)})
    return 0

if __name__=='__main__':raise SystemExit(main())
