"""Optional local mapping editor. Tk is included with the python.org Mac installer."""
import json
import os
from pathlib import Path
import tempfile
from mac_output import DEFAULT_MAPPING, KEY_CODES, load_mapping, validate_mapping


def save_mapping(path,value):
    value=validate_mapping(value);path=Path(path)
    old=path.read_bytes() if path.exists() else None
    fd,temp=tempfile.mkstemp(prefix='.Mac-Mapping-',suffix='.json',dir=path.parent)
    try:
        with os.fdopen(fd,'w',encoding='utf-8') as handle:
            json.dump(value,handle,indent=2);handle.write('\n');handle.flush();os.fsync(handle.fileno())
        if old is not None: path.with_name('Mac-Mapping.previous.json').write_bytes(old)
        os.replace(temp,path)
    finally:
        if os.path.exists(temp):os.unlink(temp)


def main():
    try:
        import tkinter as tk
        from tkinter import ttk,messagebox
    except ImportError:
        print('The mapping editor needs Tk, included in Python from https://www.python.org/downloads/macos/.')
        print('The receiver itself still works with the supplied defaults.');return 1
    path=Path(__file__).with_name('Mac-Mapping.json')
    try:value=load_mapping(path)
    except Exception as error:
        print('Mapping could not be loaded:',error);return 1
    root=tk.Tk();root.title('Simple Controller · Mac mappings');root.minsize(620,650)
    body=ttk.Frame(root,padding=20);body.pack(fill='both',expand=True)
    ttk.Label(body,text='Match your game’s keyboard and mouse controls',font=('Helvetica',17,'bold')).pack(anchor='w')
    ttk.Label(body,text='Your phone profiles stay unchanged. Stop the receiver before saving, then reopen it.\nMac outputs are keys and mouse clicks, not a native analog gamepad.',wraplength=620).pack(anchor='w',pady=(8,16))
    columns=ttk.Frame(body);columns.pack(fill='both',expand=True)
    left=ttk.Frame(columns);left.pack(side='left',fill='both',expand=True,padx=(0,22))
    right=ttk.Frame(columns);right.pack(side='left',fill='both',expand=True)
    options=['OFF','MOUSE_LEFT','MOUSE_RIGHT','MOUSE_MIDDLE']+sorted(KEY_CODES)
    variables={}
    def row(parent,title,section,key,index):
        ttk.Label(parent,text=title).grid(row=index,column=0,sticky='w',padx=(0,8),pady=4)
        variable=tk.StringVar(value=value[section][key]);variables[(section,key)]=variable
        ttk.Combobox(parent,textvariable=variable,values=options,state='readonly',width=14).grid(row=index,column=1,sticky='ew')
    for index,key in enumerate(value['buttons']):row(left,key,'buttons',key,index)
    for index,key in enumerate(value['left_stick']):row(right,'Left stick '+key,'left_stick',key,index)
    row(right,'LT (aim)','triggers','LT',5);row(right,'RT (fire)','triggers','RT',6)
    ttk.Label(right,text='Right stick → mouse camera').grid(row=8,column=0,columnspan=2,sticky='w',pady=(18,6))
    speed=tk.StringVar(value=str(value['right_stick_pixels_per_second']))
    deadzone=tk.StringVar(value=str(value['stick_deadzone']))
    threshold=tk.StringVar(value=str(value['trigger_threshold']))
    for index,(label,variable) in enumerate([('Camera speed (50–3000)',speed),('Stick deadzone (.05–.8)',deadzone),('Trigger threshold (0–.95)',threshold)],start=9):
        ttk.Label(right,text=label).grid(row=index,column=0,sticky='w',pady=4)
        ttk.Entry(right,textvariable=variable,width=12).grid(row=index,column=1,sticky='ew')
    invert=tk.BooleanVar(value=value['invert_right_stick_y'])
    ttk.Checkbutton(right,text='Invert camera up / down',variable=invert).grid(row=13,column=0,columnspan=2,sticky='w',pady=10)
    ttk.Label(body,text='LWIN / RWIN mean Command on Mac. LALT / RALT mean Option.\nOFF disables a mapping. Lower camera speed is gentler; a larger deadzone ignores small stick movement.',wraplength=620).pack(anchor='w',pady=12)
    def save():
        try:
            updated={'version':1,'buttons':{},'left_stick':{},'triggers':{},'stick_deadzone':float(deadzone.get()),
                     'trigger_threshold':float(threshold.get()),'right_stick_pixels_per_second':float(speed.get()),
                     'invert_right_stick_y':invert.get()}
            for (section,key),variable in variables.items():updated[section][key]=variable.get()
            save_mapping(path,updated)
        except Exception as error:messagebox.showerror('Could not save',str(error));return
        messagebox.showinfo('Mappings saved','Reopen Start-Mac-Receiver.command to use these mappings. Your phone layouts were not changed.');root.destroy()
    controls=ttk.Frame(body);controls.pack(fill='x')
    ttk.Button(controls,text='Save mappings',command=save).pack(side='right')
    ttk.Button(controls,text='Cancel',command=root.destroy).pack(side='right',padx=8)
    root.mainloop();return 0

if __name__=='__main__':raise SystemExit(main())
