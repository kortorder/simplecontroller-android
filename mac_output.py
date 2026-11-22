"""macOS keyboard/mouse output and configurable controller mapping.

No native libraries, permissions or event posting are touched at import. The
launcher configures QuartzOutput only after the user grants Accessibility.
This is a keyboard/mouse bridge, not a virtual Xbox/gamepad driver.
"""
from copy import deepcopy
from contextlib import contextmanager
import ctypes
import json
import math
from pathlib import Path
import threading
import time
from types import SimpleNamespace

# Apple Carbon/HIToolbox virtual key codes. These identify physical keys; the
# user's selected macOS keyboard layout determines the resulting characters.
KEY_CODES = {'A':0,'S':1,'D':2,'F':3,'H':4,'G':5,'Z':6,'X':7,'C':8,'V':9,
             'B':11,'Q':12,'W':13,'E':14,'R':15,'Y':16,'T':17,'1':18,'2':19,
             '3':20,'4':21,'6':22,'5':23,'=':24,'9':25,'7':26,'-':27,'8':28,
             '0':29,']':30,'O':31,'U':32,'[':33,'I':34,'P':35}
KEY_CODES.update({'ENTER':36,'L':37,'J':38,"'":39,'K':40,';':41,'\\':42,',':43,'/':44,
                  'N':45,'M':46,'.':47,'TAB':48,'SPACE':49,'`':50,'BACKSPACE':51,'ESC':53,
                  'RWIN':54,'LWIN':55,'LSHIFT':56,'CAPSLOCK':57,'LALT':58,'LCTRL':59,
                  'RSHIFT':60,'RALT':61,'RCTRL':62,'INSERT':114,'HOME':115,'PAGEUP':116,
                  'DELETE':117,'END':119,'PAGEDOWN':121,'LEFT':123,'RIGHT':124,'DOWN':125,'UP':126})
KEY_CODES.update(dict(zip(('F'+str(i) for i in range(1,21)),
                         [122,120,99,118,96,97,98,100,101,109,103,111,105,107,113,106,64,79,80,90])))
ALIASES = {'RETURN':'ENTER','ESCAPE':'ESC','SPACEBAR':'SPACE','SPACE BAR':'SPACE',
           'CTRL':'LCTRL','CONTROL':'LCTRL','LEFT CTRL':'LCTRL','LEFT CONTROL':'LCTRL',
           'RIGHT CTRL':'RCTRL','RIGHT CONTROL':'RCTRL','SHIFT':'LSHIFT','LEFT SHIFT':'LSHIFT',
           'RIGHT SHIFT':'RSHIFT','ALT':'LALT','OPTION':'LALT','LEFT ALT':'LALT','RIGHT ALT':'RALT',
           'WIN':'LWIN','WINDOWS':'LWIN','LEFT WINDOWS':'LWIN','RIGHT WINDOWS':'RWIN',
           'CMD':'LWIN','COMMAND':'LWIN','LEFT COMMAND':'LWIN','RIGHT COMMAND':'RWIN',
           'PAGE UP':'PAGEUP','PAGE DOWN':'PAGEDOWN','CAPS LOCK':'CAPSLOCK',
           'PLUS':'+','MINUS':'-','COMMA':',','PERIOD':'.','SLASH':'/','BACKSLASH':'\\',
           'SEMICOLON':';','APOSTROPHE':"'",'GRAVE':'`','LEFT BRACKET':'[','RIGHT BRACKET':']'}
# Plus is a shifted equals key; explicit SHIFT+EQUAL is preferable in payloads.
ALIASES['EQUAL'] = '='
MODIFIER_FLAGS = {'LSHIFT':1<<17,'RSHIFT':1<<17,'LCTRL':1<<18,'RCTRL':1<<18,
                  'LALT':1<<19,'RALT':1<<19,'LWIN':1<<20,'RWIN':1<<20}
BUTTONS = ['A','B','X','Y','LB','RB','START','BACK','UP','DOWN','LEFT','RIGHT','LS','RS']
DEFAULT_MAPPING = {
    'version':1,
    'buttons':{'A':'SPACE','B':'LCTRL','X':'R','Y':'E','LB':'Q','RB':'G',
               'START':'ESC','BACK':'TAB','UP':'UP','DOWN':'DOWN','LEFT':'LEFT',
               'RIGHT':'RIGHT','LS':'LSHIFT','RS':'V'},
    'left_stick':{'up':'W','down':'S','left':'A','right':'D'},
    'triggers':{'LT':'MOUSE_RIGHT','RT':'MOUSE_LEFT'},
    'stick_deadzone':0.20,'trigger_threshold':0.0,'right_stick_pixels_per_second':900.0,
    'invert_right_stick_y':False,
}


def canonical_key(key):
    if not isinstance(key,str): raise ValueError('Key names must be text')
    if key == ' ': return 'SPACE'
    value = key.strip().upper()
    value = ALIASES.get(value,value)
    if value not in KEY_CODES: raise ValueError('Unsupported Mac key: '+key)
    return value


def canonical_target(value):
    if value == 'OFF': return value
    if value in ('MOUSE_LEFT','MOUSE_RIGHT','MOUSE_MIDDLE'): return value
    return canonical_key(value)


def validate_mapping(value):
    if not isinstance(value,dict) or set(value) != set(DEFAULT_MAPPING):
        raise ValueError('Mapping must contain exactly the fields in Mac-Mapping.json')
    if type(value['version']) is not int or value['version'] != 1: raise ValueError('Unknown mapping version')
    result = deepcopy(value)
    for section in ('buttons','left_stick','triggers'):
        if not isinstance(value[section],dict) or set(value[section]) != set(DEFAULT_MAPPING[section]):
            raise ValueError('Invalid '+section+' mapping keys')
        result[section] = {key:canonical_target(target) for key,target in value[section].items()}
    for key,low,high in [('stick_deadzone',0.05,.8),('trigger_threshold',0,.95),
                         ('right_stick_pixels_per_second',50,3000)]:
        number = value[key]
        if type(number) not in (float,int) or not math.isfinite(number) or not low <= number <= high:
            raise ValueError('Invalid '+key)
    if type(value['invert_right_stick_y']) is not bool: raise ValueError('Invalid invert_right_stick_y')
    return result


def load_mapping(path):
    path = Path(path)
    if path.stat().st_size > 16384: raise ValueError('Mapping file is too large')
    def unique(items):
        result = {}
        for key,value in items:
            if key in result: raise ValueError('Duplicate mapping key: '+key)
            result[key] = value
        return result
    return validate_mapping(json.loads(path.read_text(encoding='utf-8'),object_pairs_hook=unique))


class CGPoint(ctypes.Structure):
    _fields_ = [('x',ctypes.c_double),('y',ctypes.c_double)]


class QuartzOutput:
    """Small stdlib-only CoreGraphics binding. Accessibility is checked first."""
    def __init__(self, cg=None, cf=None, ax=None):
        self.cg = cg or ctypes.CDLL('/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics')
        self.cf = cf or ctypes.CDLL('/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation')
        self.ax = ax or ctypes.CDLL('/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices')
        self._function(self.ax,'AXIsProcessTrusted',[],ctypes.c_bool)
        self._function(self.cg,'CGEventCreate',[ctypes.c_void_p],ctypes.c_void_p)
        self._function(self.cg,'CGEventCreateKeyboardEvent',[ctypes.c_void_p,ctypes.c_uint16,ctypes.c_bool],ctypes.c_void_p)
        self._function(self.cg,'CGEventCreateMouseEvent',[ctypes.c_void_p,ctypes.c_uint32,CGPoint,ctypes.c_uint32],ctypes.c_void_p)
        # Variadic wheel value has an explicit c_int32 below; fixed args here.
        self._function(self.cg,'CGEventCreateScrollWheelEvent',[ctypes.c_void_p,ctypes.c_uint32,ctypes.c_uint32],ctypes.c_void_p)
        self._function(self.cg,'CGEventGetLocation',[ctypes.c_void_p],CGPoint)
        self._function(self.cg,'CGEventSetIntegerValueField',[ctypes.c_void_p,ctypes.c_uint32,ctypes.c_int64],None)
        self._function(self.cg,'CGEventSetFlags',[ctypes.c_void_p,ctypes.c_uint64],None)
        self._function(self.cg,'CGEventPost',[ctypes.c_uint32,ctypes.c_void_p],None)
        self._function(self.cf,'CFRelease',[ctypes.c_void_p],None)
        self.keys = set(); self.mouse_buttons = set()

    @staticmethod
    def _function(library,name,args,result):
        function = getattr(library,name); function.argtypes=args; function.restype=result

    def trusted(self):
        return bool(self.ax.AXIsProcessTrusted())

    def _event(self,event,flags=True):
        if not event: raise OSError('macOS could not create the input event')
        try:
            if not self.trusted(): raise PermissionError('Accessibility access was removed; receiver output stopped')
            if flags: self.cg.CGEventSetFlags(event,self.flags())
            # kCGHIDEventTap. Event posting is only called after a startup
            # Accessibility check. Never request Input Monitoring or root.
            self.cg.CGEventPost(0,event)
        finally:
            self.cf.CFRelease(event)

    def flags(self):
        flags = 0
        for key in self.keys: flags |= MODIFIER_FLAGS.get(key,0)
        return flags

    def key(self,key,down):
        key = canonical_key(key)
        if down: self.keys.add(key)
        else: self.keys.discard(key)
        self._event(self.cg.CGEventCreateKeyboardEvent(None,KEY_CODES[key],bool(down)))

    def _position(self):
        event = self.cg.CGEventCreate(None)
        if not event: raise OSError('macOS could not read the pointer position')
        try: return self.cg.CGEventGetLocation(event)
        finally: self.cf.CFRelease(event)

    def mouse(self,button,down):
        index = {'LEFT':0,'RIGHT':1,'MIDDLE':2}[button]
        event_type = ({'LEFT':1,'RIGHT':3,'MIDDLE':25} if down else {'LEFT':2,'RIGHT':4,'MIDDLE':26})[button]
        if down: self.mouse_buttons.add(button)
        else: self.mouse_buttons.discard(button)
        self._event(self.cg.CGEventCreateMouseEvent(None,event_type,self._position(),index))

    def move(self,dx,dy):
        point = self._position(); point.x += dx; point.y += dy
        button = next((b for b in ('LEFT','RIGHT','MIDDLE') if b in self.mouse_buttons),None)
        event_type = {'LEFT':6,'RIGHT':7,'MIDDLE':27,None:5}[button]
        index = {'LEFT':0,'RIGHT':1,'MIDDLE':2,None:0}[button]
        event = self.cg.CGEventCreateMouseEvent(None,event_type,point,index)
        if not event: raise OSError('macOS could not create pointer movement')
        self.cg.CGEventSetIntegerValueField(event,4,int(dx)) # kCGMouseEventDeltaX
        self.cg.CGEventSetIntegerValueField(event,5,int(dy)) # kCGMouseEventDeltaY
        self._event(event)

    def wheel(self,ticks):
        # kCGScrollEventUnitLine, one vertical wheel, signed line count.
        self._event(self.cg.CGEventCreateScrollWheelEvent(None,1,1,ctypes.c_int32(int(ticks))))


class MacRuntime:
    """Source-owned mapped keys; releasing one source never cancels another."""
    def __init__(self,output,mapping=None,clock=time.monotonic):
        self.output=output; self.mapping=validate_mapping(mapping or DEFAULT_MAPPING)
        self.clock=clock; self.lock=threading.RLock(); self.sources={}; self.applied=set()
        self.pads=[]; self.remainder=[0.,0.]; self.last_tick=clock(); self.stop=threading.Event()
        self.thread=None; self.failure=None; self.batch_depth=0; self.pending_motion=[]
        self.pointer_fraction=[0.,0.];self.wheel_fraction=0.

    def set_source(self,source,targets):
        with self.lock:
            if self.failure: raise OSError('Mac output stopped after a native error') from self.failure
            targets = {canonical_target(t) for t in targets} - {'OFF'}
            if targets: self.sources[source]=targets
            else: self.sources.pop(source,None)
            if not self.batch_depth: self._sync()

    @staticmethod
    def _press_order(target):
        return (0 if target in MODIFIER_FLAGS else 1,target)

    def _sync(self):
        desired=set().union(*self.sources.values()) if self.sources else set()
        try:
            # Key-up before modifier-up; modifier-down before a key or click.
            for target in sorted(self.applied-desired,key=lambda t:(t in MODIFIER_FLAGS,t)):
                self._write(target,False); self.applied.remove(target)
            for target in sorted(desired-self.applied,key=self._press_order):
                self.applied.add(target); self._write(target,True)
        except Exception as error:
            self.failure=error;self.stop.set()
            # Continue attempting all releases, including a partly written press.
            try: self.release_all()
            except Exception: pass
            raise

    @contextmanager
    def batch(self):
        """Apply one SCPC1 snapshot atomically, including mapped modifiers."""
        with self.lock:
            self.batch_depth+=1
            try: yield
            finally:
                self.batch_depth-=1
                if not self.batch_depth:
                    self._sync()
                    pending,self.pending_motion=self.pending_motion,[]
                    for kind,args in pending:
                        (self.move if kind=='move' else self.wheel)(*args)

    def _write(self,target,down):
        if target.startswith('MOUSE_'): self.output.mouse(target[6:],down)
        else: self.output.key(target,down)

    def move(self,dx,dy):
        with self.lock:
            if self.failure: raise OSError('Mac output stopped after a native error') from self.failure
            if not all(math.isfinite(v) and abs(v)<=4096 for v in (dx,dy)):
                raise ValueError('Mac pointer delta must be finite and at most 4096 pixels')
            if self.batch_depth: self.pending_motion.append(('move',(dx,dy)));return
            delta=[dx+self.pointer_fraction[0],dy+self.pointer_fraction[1]]
            steps=[int(v) for v in delta]
            self.pointer_fraction=[v-step for v,step in zip(delta,steps)]
            if any(steps): self.output.move(*steps)

    def wheel(self,ticks):
        with self.lock:
            if self.failure: raise OSError('Mac output stopped after a native error') from self.failure
            if not math.isfinite(ticks) or abs(ticks)>4096:raise ValueError('Mac scroll delta is invalid')
            if self.batch_depth: self.pending_motion.append(('wheel',(ticks,)));return
            total=ticks+self.wheel_fraction;whole=int(total);self.wheel_fraction=total-whole
            if whole:self.output.wheel(whole)

    def clear_motion_remainders(self):
        with self.lock:
            self.remainder=[0.,0.];self.pointer_fraction=[0.,0.];self.wheel_fraction=0.;self.pending_motion=[]

    def tick(self):
        with self.lock:
            now=self.clock(); elapsed=min(.05,max(0,now-self.last_tick));self.last_tick=now
            # Player1 is the recommended Mac route. A second player shares the
            # desktop; use the largest active vector rather than doubling speed.
            vector=max((pad.right for pad in self.pads),key=lambda p:math.hypot(*p),default=(0.,0.))
            size=math.hypot(*vector); deadzone=self.mapping['stick_deadzone']
            if size <= deadzone:
                self.remainder=[0.,0.]; return
            scale=min(1.,(size-deadzone)/(1-deadzone))/size
            speed=self.mapping['right_stick_pixels_per_second']*elapsed
            ysign=1 if self.mapping['invert_right_stick_y'] else -1
            delta=[vector[0]*scale*speed+self.remainder[0],vector[1]*scale*speed*ysign+self.remainder[1]]
            steps=[int(v) for v in delta]
            self.remainder=[v-s for v,s in zip(delta,steps)]
            self.move(*steps)

    def start(self):
        def run():
            while not self.stop.wait(1/120):
                try: self.tick()
                except Exception as error:
                    self.failure=error; self.stop.set()
                    try: self.release_all()
                    except Exception: pass
        self.thread=threading.Thread(target=run,daemon=True,name='Mac camera pointer');self.thread.start()

    def release_all(self):
        with self.lock:
            self.sources.clear()
            for pad in self.pads: pad.buttons.clear();pad.left=(0.,0.);pad.right=(0.,0.);pad.triggers={'LT':0.,'RT':0.}
            self.clear_motion_remainders()
            errors=[]
            for target in sorted(self.applied,key=lambda t:(t in MODIFIER_FLAGS,t)):
                try: self._write(target,False);self.applied.remove(target)
                except Exception as error: errors.append(error)
            if errors: raise errors[0]

    def close(self):
        self.stop.set()
        if self.thread and self.thread is not threading.current_thread(): self.thread.join(timeout=1)
        self.release_all()


_runtime=None

def configure(output,mapping=None,clock=time.monotonic):
    global _runtime
    if _runtime is not None: _runtime.close()
    _runtime=MacRuntime(output,mapping,clock);return _runtime

def runtime():
    if _runtime is None: raise RuntimeError('Start this receiver with Start-Mac-Receiver.command')
    return _runtime

class MappedGamepad:
    def __init__(self):
        self.runtime=runtime(); self.source='pad:'+str(len(self.runtime.pads)); self.runtime.pads.append(self)
        self.buttons=set();self.left=(0.,0.);self.right=(0.,0.);self.triggers={'LT':0.,'RT':0.}
    def press_button(self,button): self.buttons.add(button)
    def release_button(self,button): self.buttons.discard(button)
    def left_joystick_float(self,x_value_float,y_value_float): self.left=(x_value_float,y_value_float)
    def right_joystick_float(self,x_value_float,y_value_float): self.right=(x_value_float,y_value_float)
    def left_trigger_float(self,value_float): self.triggers['LT']=value_float
    def right_trigger_float(self,value_float): self.triggers['RT']=value_float
    def reset(self):
        self.buttons.clear();self.left=(0.,0.);self.right=(0.,0.);self.triggers={'LT':0.,'RT':0.}
    def update(self):
        mapping=self.runtime.mapping
        desired={mapping['buttons'][b] for b in self.buttons}
        x,y=self.left;threshold=mapping['stick_deadzone']
        for direction,active in [('left',x < -threshold),('right',x > threshold),('up',y > threshold),('down',y < -threshold)]:
            if active: desired.add(mapping['left_stick'][direction])
        for trigger,value in self.triggers.items():
            if value > mapping['trigger_threshold']: desired.add(mapping['triggers'][trigger])
        self.runtime.set_source(self.source,desired)

class MacKeyboard:
    def press(self,key):
        key=canonical_key(key);runtime().set_source('key:'+key,{key})
    def release(self,key):
        key=canonical_key(key);runtime().set_source('key:'+key,set())
    def is_pressed(self,key):
        return canonical_key(key) in runtime().sources.get('key:'+canonical_key(key),set())
    def press_and_release(self,key):
        # The receiver's raw Android chord syntax uses '+'. Each key gets its
        # own source; regular profile controls already use ordered DOWN/UP.
        keys=[canonical_key(k) for k in key.split('+')]
        source='tap:'+str(time.monotonic_ns())
        try: runtime().set_source(source,set(keys));time.sleep(.05)
        finally: runtime().set_source(source,set())

class MacMouse:
    FAILSAFE=True; PAUSE=0
    def mouseDown(self,button='left'):
        target='MOUSE_'+button.upper();runtime().set_source('mouse:'+target,{target})
    def mouseUp(self,button='left'):
        target='MOUSE_'+button.upper();runtime().set_source('mouse:'+target,set())
    def moveRel(self,x,y):runtime().move(x,y)
    def scroll(self,ticks):runtime().wheel(ticks)
    def wheel(self,ticks):runtime().wheel(ticks)

_button_attributes={'A':'A','B':'B','X':'X','Y':'Y','LEFT_SHOULDER':'LB','RIGHT_SHOULDER':'RB',
                    'START':'START','BACK':'BACK','DPAD_UP':'UP','DPAD_DOWN':'DOWN','DPAD_LEFT':'LEFT',
                    'DPAD_RIGHT':'RIGHT','LEFT_THUMB':'LS','RIGHT_THUMB':'RS'}
vgamepad=SimpleNamespace(VX360Gamepad=MappedGamepad,
                        XUSB_BUTTON=SimpleNamespace(**{'XUSB_GAMEPAD_'+k:v for k,v in _button_attributes.items()}))
keyboard=MacKeyboard();mouse=MacMouse();pyautogui=mouse

def send_relative_mouse(dx,dy):runtime().move(dx,dy)
