"""Translate SCPC1 snapshots into the receiver's existing phone ownership layer."""
from urllib.parse import quote_plus
from ios_pc_protocol import IOSPCProtocol

BUTTON_NAMES = {'a':'A','b':'B','x':'X','y':'Y','leftBumper':'LB','rightBumper':'RB',
                'view':'BACK','menu':'START','leftStick':'LS','rightStick':'RS'}
DPAD_NAMES = {'neutral':(), 'up':('UP',), 'upRight':('UP','RIGHT'), 'right':('RIGHT',),
              'downRight':('DOWN','RIGHT'),'down':('DOWN',),'downLeft':('DOWN','LEFT'),
              'left':('LEFT',),'upLeft':('UP','LEFT')}

def commands(output):
    pad = output['gamepad']
    buttons = {BUTTON_NAMES[b] for b in pad['buttons']} | set(DPAD_NAMES[pad['dpad']])
    result = {f'player1|XBOX|{b}':f'X360{b}_'+('HOLD' if b in buttons else 'RELEASE')
              for b in list(BUTTON_NAMES.values())+['UP','DOWN','LEFT','RIGHT']}
    # iOS uses up-positive Y; the original Android receiver expects down-positive
    # screen coordinates and flips Y when sending its virtual gamepad report.
    for side, field in [('L','leftStick'),('R','rightStick')]:
        result[f'player1|MANUAL|{side}'] = f"STICK_{side}:{pad[field]['x']},{-pad[field]['y']}"
    for tag, field in [('LT','leftTrigger'),('RT','rightTrigger')]:
        result[f'player1|TRIGGER|{tag}'] = f'{tag}:{output[field]}'
    for key in output['keys']:
        result[f'GLOBAL|KEY|{key}'] = 'KEY_DOWN:'+key
    for button in ['LEFT','RIGHT','MIDDLE']:
        result[f'GLOBAL|MOUSE|{button}'] = f'MOUSE_{button}_'+('DOWN' if button in output['mouse'] else 'UP')
    return result

class IOSPCAdapter:
    def __init__(self, phone_protocol, neutralize, relative_mouse, wheel, lock, clock=None):
        self.phone = phone_protocol
        self.neutralize, self.relative_mouse, self.wheel = neutralize, relative_mouse, wheel
        self.pointer_remainder = [0.,0.]
        self.scroll_remainder = 0.
        options = {'lock':lock}
        if clock is not None: options['clock'] = clock
        self.protocol = IOSPCProtocol(self.activate, self.apply, self.release, **options)

    def activate(self, session):
        self.phone.process(f'PHONE_CAPS_V1:{session}:ios', 'player1')
        handled, response = self.phone.process(f'PHONE_ACTIVATE_V1:{session}:0','player1')
        if not handled or response != f'PHONE_ACK_V1:{session}:0':
            raise RuntimeError('PC phone output could not be activated')
        self.pointer_remainder = [0.,0.]
        self.scroll_remainder = 0.
        # Camera Follow is computed by the iPhone; do not generate a second one.
        self.phone.process(f'PHONE_STATE_V1:{session}:1:player1%7CCAMERA%7CENABLED:CAMERA_FOLLOW%3A0','player1')

    def apply(self, output, previous, session, sequence):
        if self.phone.players.get('player1') != session:
            raise RuntimeError('Another phone owns player 1')
        current, before = commands(output), commands(previous)
        for key in set(before)-set(current):
            if '|KEY|' in key: current[key] = 'KEY_UP:'+key.split('|')[-1]
        for index,(key,command) in enumerate(sorted(current.items())):
            if before.get(key) == command: continue
            seq = sequence*256+index+2
            handled,response = self.phone.process(
                f'PHONE_STATE_V1:{session}:{seq}:{quote_plus(key)}:{quote_plus(command)}','player1')
            if not handled or not response or not response.startswith(f'PHONE_ACK_V1:{session}:{seq}'):
                raise RuntimeError('PC phone output was refused')
        delta = [output['pointer'][i]-previous['pointer'][i]+self.pointer_remainder[i] for i in range(2)]
        steps = [int(v) for v in delta]
        if any(steps): self.relative_mouse(*steps)
        self.pointer_remainder = [delta[i]-steps[i] for i in range(2)]
        # Forty screen points make one wheel notch. Preserve fractional travel.
        scroll = (output['scroll']-previous['scroll'])/40+self.scroll_remainder
        ticks = int(scroll)
        if ticks: self.wheel(ticks)
        self.scroll_remainder = scroll-ticks

    def release(self, session):
        # Do not release another Android phone, face or voice owner's input.
        if self.phone.players.get('player1') == session:
            self.phone.clear_outputs(('player1',))
            self.neutralize('player1')
            self.phone.retired.add(session)
        self.pointer_remainder = [0.,0.]
        self.scroll_remainder = 0.
