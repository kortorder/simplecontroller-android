"""SCPC1: bounded, ordered iPhone snapshots with a 500 ms phone-only lease.

No native imports or input injection at import. Existing Android commands are
not intercepted. Call expire() at least every 50 ms while the server runs.
This is a trusted-local-network protocol, not Internet authentication.
"""
import json
import math
import threading
import time
import uuid
from collections import OrderedDict

LEASE_SECONDS = 0.5
MAX_BYTES = 8192
MAX_SEQUENCE = 9007199254740991
BUTTONS = {'a', 'b', 'x', 'y', 'leftBumper', 'rightBumper', 'view', 'menu', 'leftStick', 'rightStick'}
DPAD = {'neutral', 'up', 'upRight', 'right', 'downRight', 'down', 'downLeft', 'left', 'upLeft'}
KEYS = set('ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789') | {
    'SPACE','ENTER','ESC','TAB','BACKSPACE','UP','DOWN','LEFT','RIGHT','HOME','END',
    'PAGEUP','PAGEDOWN','INSERT','DELETE','CAPSLOCK','LSHIFT','RSHIFT','LCTRL','RCTRL',
    'LALT','RALT','LWIN','RWIN'} | {'F'+str(i) for i in range(1,13)}
MOUSE = {'LEFT', 'RIGHT', 'MIDDLE'}

def _identity(value):
    if not isinstance(value, str) or len(value) != 36:
        return False
    try:
        return str(uuid.UUID(value)).upper() == value.upper()
    except (ValueError, AttributeError):
        return False

def _number(value, low, high):
    return type(value) in (int, float) and low <= value <= high and math.isfinite(value)

def _strings(value, allowed):
    return (isinstance(value, list) and len(value) <= len(allowed)
            and all(isinstance(v, str) and v in allowed for v in value)
            and len(value) == len(set(value)))

def valid_output(output):
    if not isinstance(output, dict) or set(output) != {'gamepad','leftTrigger','rightTrigger','keys','mouse','pointer','scroll'}:
        return False
    pad = output['gamepad']
    if not isinstance(pad, dict) or set(pad) != {'buttons','leftStick','rightStick','dpad'}:
        return False
    if not _strings(pad['buttons'], BUTTONS) or not isinstance(pad['dpad'], str) or pad['dpad'] not in DPAD:
        return False
    for side in ('leftStick','rightStick'):
        axis = pad[side]
        if not isinstance(axis,dict) or set(axis) != {'x','y'} or not all(_number(v,-1,1) for v in axis.values()):
            return False
    return (_number(output['leftTrigger'],0,1) and _number(output['rightTrigger'],0,1)
            and _strings(output['keys'],KEYS) and _strings(output['mouse'],MOUSE)
            and isinstance(output['pointer'],list) and len(output['pointer']) == 2
            and all(_number(v,-1e12,1e12) for v in output['pointer'])
            and _number(output['scroll'],-1e12,1e12))

def neutral_output():
    return {'gamepad':{'buttons':[], 'leftStick':{'x':0,'y':0}, 'rightStick':{'x':0,'y':0}, 'dpad':'neutral'},
            'leftTrigger':0,'rightTrigger':0,'keys':[],'mouse':[],'pointer':[0,0],'scroll':0}

def _unique_object(pairs):
    result = {}
    for key,value in pairs:
        if key in result:
            raise ValueError('Duplicate JSON key')
        result[key] = value
    return result

class IOSPCProtocol:
    def __init__(self, activate, apply, release, lock=None, clock=time.monotonic, token_factory=lambda: str(uuid.uuid4())):
        self.activate, self.apply, self.release = activate, apply, release
        self.lock = lock or threading.RLock()
        self.clock, self.token_factory = clock, token_factory
        self.offers = OrderedDict()
        self.retired = OrderedDict()
        self.active = None
        self.previous = neutral_output()

    def _retire(self, session):
        self.offers.pop(session, None)
        self.retired[session] = True
        while len(self.retired) > 256:
            self.retired.popitem(last=False)
        if self.active == session:
            self.active = None
            self.previous = neutral_output()
            self.release(session)

    def expire(self):
        with self.lock:
            now = self.clock()
            for session, offer in list(self.offers.items()):
                if now >= offer['deadline']:
                    self._retire(session)

    def process(self, data, address):
        # Plain Android/CB commands take their original path unchanged.
        if not isinstance(data,str) or not data.lstrip().startswith('{'):
            return False, None
        if len(data.encode('utf-8')) > MAX_BYTES:
            return True, None
        try:
            p = json.loads(data, object_pairs_hook=_unique_object)
        except (ValueError, TypeError, RecursionError, OverflowError):
            return True, None
        if not isinstance(p,dict) or p.get('protocolVersion') != 'SCPC1':
            return False, None
        kind, session, seq = p.get('kind'), p.get('session'), p.get('sequence')
        if not _identity(session) or type(seq) is not int or not 0 <= seq <= MAX_SEQUENCE:
            return True, None
        with self.lock:
            self.expire()
            now = self.clock()
            if session in self.retired:
                return True, None
            if kind == 'hello':
                if set(p) != {'protocolVersion','kind','session','sequence'} or seq != 0:
                    return True,None
                offer = self.offers.get(session)
                if offer is None:
                    if len(self.offers) >= 32:
                        return True,None
                    offer = {'token':self.token_factory(),'address':address,'deadline':now+3,'sequence':0}
                    self.offers[session] = offer
                if offer['address'] != address:
                    return True,None
                return True, self._packet('ready',session,0,offer['token'])
            offer = self.offers.get(session)
            if not offer or offer['address'] != address or p.get('token') != offer['token'] or seq < 1:
                return True,None
            keys = {'protocolVersion','kind','session','sequence','token'}
            if kind == 'stop':
                if set(p) != keys or seq <= offer['sequence']:
                    return True,None
                self._retire(session)
                return True,None
            if kind != 'state' or set(p) != keys | {'output'} or not valid_output(p['output']):
                return True,None
            if seq <= offer['sequence']:
                # A duplicate ACK is safe but deliberately does not extend the lease.
                return True,self._packet('ack',session,seq,offer['token'])
            output = p['output']
            if self.active != session:
                if seq != 1 or output != neutral_output():
                    return True,None
                if self.active is not None:
                    self._retire(self.active)
                try:
                    self.activate(session)
                except Exception:
                    self._retire(session)
                    return True,self._packet('error',session,seq,offer['token'])
                self.active = session
                self.previous = neutral_output()
            # Cursor deltas are bounded. Malformed jumps fail closed without output.
            if any(abs(a-b)>4096 for a,b in zip(output['pointer'], self.previous['pointer'])) or abs(output['scroll']-self.previous['scroll'])>4096:
                self._retire(session)
                return True,self._packet('error',session,seq,offer['token'])
            try:
                self.apply(output,self.previous,session,seq)
            except Exception:
                self._retire(session)
                return True,self._packet('error',session,seq,offer['token'])
            self.previous = output
            offer['sequence'] = seq
            offer['deadline'] = now + LEASE_SECONDS
            return True,self._packet('ack',session,seq,offer['token'])

    @staticmethod
    def _packet(kind,session,sequence,token):
        return json.dumps({'protocolVersion':'SCPC1','kind':kind,'session':session,'sequence':sequence,'token':token},separators=(',',':'))
