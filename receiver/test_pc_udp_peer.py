"""Local recording peer for development. Never injects host input or imports Windows code."""
import argparse
import json
import signal
import socket
import time
from ios_pc_protocol import IOSPCProtocol

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--port',type=int,default=42734)
    parser.add_argument('--record',required=True)
    args=parser.parse_args()
    record=open(args.record,'a',buffering=1)
    def log(kind,**values):record.write(json.dumps(dict(time=time.monotonic(),kind=kind,**values),sort_keys=True)+'\n')
    def apply(output,previous,session,sequence):
        if output!=previous:log('state',session=session,sequence=sequence,output=output)
    peer=IOSPCProtocol(lambda s:log('neutral',session=s),apply,lambda s:log('release',session=s))
    sock=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
    sock.bind(('127.0.0.1',args.port));sock.settimeout(.025)
    print('Recording-only SCPC1 peer on 127.0.0.1:'+str(sock.getsockname()[1]),flush=True)
    running=True
    def stop(*_):
        nonlocal running
        running=False
    signal.signal(signal.SIGTERM,stop);signal.signal(signal.SIGINT,stop)
    try:
        while running:
            peer.expire()
            try:data,address=sock.recvfrom(8193)
            except socket.timeout:continue
            try:handled,response=peer.process(data.decode('utf-8'),address)
            except UnicodeError:continue
            if handled and response:sock.sendto(response.encode('utf-8'),address)
    finally:
        if peer.active:peer._retire(peer.active)
        sock.close();record.close()

if __name__=='__main__':main()
