#!/usr/bin/env python3
"""Generate 300 independently labelled November-15 sheets per physical layout.

The PDF vector map supplies truth; OMR templates/results are never consulted.
X/Y are out-of-plane rotations, Z is roll: pinhole projection Rz @ Ry @ Rx.
"""
import argparse, hashlib, json, random
from pathlib import Path
from concurrent.futures import ProcessPoolExecutor

import cv2
import numpy as np
import qrcode
from PIL import Image, ImageDraw
from generate_rotation_holdout import projection

ROOT=Path(__file__).resolve().parents[1]

def generate(arguments):
    count,output,seed,total,holdout=arguments
    cv2.setNumThreads(1)
    folder=Path(output)/str(count);folder.mkdir(parents=True,exist_ok=True)
    vector=ROOT/f'output/new-sheets-1.1.41/calibration/{count}q-vector-map.json'
    truth=json.loads(vector.read_text());pw,ph=truth['pagePt'];sx,sy=1750/pw,2480/ph
    blank=Image.open(ROOT/f'tmp/pdfs/new-sheets/generic-{count}.png').convert('RGB')
    rng=random.Random(seed+count)
    poses=[(0,0,0)]
    for axis in (0,1):
        for angle in (-60,-50,-40,-30,-20,-10,10,20,30,40,50,60):
            xyz=[0,0,0];xyz[axis]=angle;poses.append(tuple(xyz))
    poses += [(0,0,z) for z in (-180,-150,-120,-90,-60,-45,-30,-15,15,30,45,60,90,120,150,180)]
    poses += [(x,y,z) for x in (-35,-20,20,35) for y in (-35,-20,20,35) for z in (0,45,90,135,180,225,270,315)]
    poses += [tuple(round(rng.uniform(-lim,lim),2) for lim in (48,48,180)) for _ in range(300-len(poses))]
    if holdout:poses=[tuple(round(rng.uniform(-lim,lim),2) for lim in (45,45,180)) for _ in range(total)]
    else:poses=poses[:total]
    cases=[]
    for index,xyz in enumerate(poses):
        local=random.Random(seed+count*1000+index)
        mode='mixed' if index<41 and not holdout else ('blank','black','blue','green','red','pencil','light-pencil','partial','cross','multiple','mixed','erased')[index%12]
        effect='clear' if index<41 and not holdout else ('clear','shadow','dim','soft','warm','noise','far','jpeg')[(index//12)%8 if holdout else index%8]
        image=blank.copy();draw=ImageDraw.Draw(image);expected=[]
        for question in truth['questions']:
            number=question['number'];choice=local.randint(1,4)
            empty=mode=='blank' or (mode!='cross' and local.random()<.13)
            choices=[] if empty else sorted({choice,choice%4+1} if mode=='multiple' or (mode=='mixed' and local.random()<.10) else {choice})
            if holdout and mode=='multiple' and choices:choices=sorted(local.sample([1,2,3,4],2+(index//12)%3))
            expected.append(choices)
            if mode=='erased' and empty:
                option=next(o for o in question['options'] if o['value']==choice)
                x,y=option['center_pt'];r=option['radius_pt']*.66
                draw.ellipse(((x-r)*sx,(y-r)*sy,(x+r)*sx,(y+r)*sy),fill=(232,232,232))
            for value in choices:
                option=next(o for o in question['options'] if o['value']==value)
                x,y=option['center_pt'];r=option['radius_pt']*(.70 if mode=='partial' else .80)
                ink={'black':(20,20,20),'blue':(24,60,155),'green':(25,95,60),'red':(175,30,35),'pencil':(105,105,105),'light-pencil':(160,160,160)}.get(mode,[(25,25,25),(25,65,150),(20,100,65),(100,100,100)][number%4])
                if mode=='cross':
                    for direction in (-1,1):
                        draw.line(((x-r)*sx,(y-direction*r)*sy,(x+r)*sx,(y+direction*r)*sy),fill=ink,width=max(2,round(option['radius_pt']*.30*sx)))
                else:draw.ellipse(((x-r)*sx,(y-r)*sy,(x+r)*sx,(y+r)*sy),fill=ink)
        qr=f'{"T3" if count==40 else "T1"}:{count}{index:04d}:Z{count:03d}{index:04d}'
        family,student,identifier=qr.split(':')
        assert family in ('T1','T3') and student.isascii() and student.isdigit() and len(identifier)==8 and identifier.isalnum()
        code=qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M,box_size=8,border=4)
        code.add_data(qr);code.make(fit=True)
        x,y,w,h=truth['qrWhitePt']
        image.paste(code.make_image().convert('RGB').resize((round(w*sx),round(h*sy)),Image.Resampling.NEAREST),(round(x*sx),round(y*sy)))
        center=(1200+local.uniform(-45,45),1300+local.uniform(-45,45));distance=330.
        while True:
            matrix=projection(*xyz,distance=distance,center=center)
            quad=cv2.perspectiveTransform(np.float32([[[0,0],[148,0],[148,210],[0,210]]]),matrix)[0]
            if np.all(quad>=[100,100]) and np.all(quad<=[2300,2500]):break
            distance+=5
        if effect=='far':distance*=1.30;matrix=projection(*xyz,distance=distance,center=center)
        pixels=cv2.warpPerspective(np.asarray(image),matrix@np.diag([148/1750,210/2480,1.]),(2400,2600),borderValue=(65,70,75))
        if effect=='shadow':pixels=np.uint8(pixels.astype(np.float32)*np.linspace(.48,.96,2400,dtype=np.float32)[None,:,None])
        if effect=='dim':pixels=np.uint8(pixels.astype(np.float32)*.52+4)
        if effect=='soft':pixels=cv2.GaussianBlur(pixels,(0,0),.85)
        if effect=='warm':pixels=np.uint8(pixels.astype(np.float32)*np.float32([.97,.84,.67])[None,None,:])
        if effect=='noise':
            noise=np.random.default_rng(seed+count*1000+index).normal(0,2,(2600,2400,1)).astype(np.float32)
            pixels=np.uint8(np.clip(pixels.astype(np.float32)+noise,0,255))
        quality=83 if effect=='jpeg' else 95
        name=f'xyz-{count}-{index:03d}';path=(folder/f'{name}.jpg').resolve()
        Image.fromarray(pixels).save(path,quality=quality)
        actual_quad=cv2.perspectiveTransform(np.float32([[[0,0],[148,0],[148,210],[0,210]]]),matrix)[0]
        cases.append(dict(name=name,path=str(path),count=count,qr=qr,expectedOptions=expected,pattern=mode,effect=effect,
            rotationXYZ=list(xyz),distanceMm=distance,focalPx=2750,pageQuad=actual_quad.tolist(),mmToImage=matrix.tolist(),
            vectorMap=str(vector),sourcePdfSha256=truth['sourcePdfSha256'],jpegQuality=quality,sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
        if (index+1)%50==0:print(f'{count}Q: generated {index+1}/{total}',flush=True)
    (folder/'manifest.json').write_text(json.dumps(dict(seed=seed,cases=cases),indent=2)+'\n')
    return cases

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path,required=True);parser.add_argument('--seed',type=int,default=144900)
    parser.add_argument('--per-type',type=int,default=300);parser.add_argument('--holdout',action='store_true')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    with ProcessPoolExecutor(max_workers=3) as pool:groups=list(pool.map(generate,[(n,str(args.output.resolve()),args.seed,args.per_type,args.holdout) for n in (40,75,100)]))
    cases=[c for group in groups for c in group]
    assert len(cases)==3*args.per_type and all(sum(c['count']==n for c in cases)==args.per_type for n in (40,75,100))
    (args.output/'manifest.json').write_text(json.dumps(dict(seed=args.seed,convention='pinhole camera Rz @ Ry @ Rx; X/Y/Z in degrees; origin at paper center',cases=cases),indent=2)+'\n')
    print(f'{len(cases)} unique independently labelled photographs generated.',flush=True)

if __name__=='__main__':main()
