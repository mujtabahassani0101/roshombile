#!/usr/bin/env python3
"""Independent known-answer sheets, including blanks and light erased traces."""
import argparse,hashlib,json,random
from pathlib import Path
import cv2
import numpy as np
from PIL import Image,ImageDraw
import qrcode

ROOT=Path(__file__).resolve().parents[1]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--seed',type=int,default=873491)
    a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
    cases=[];sx,sy=1750/148,2480/210
    patterns=['mixed-ink','graphite-214','graphite-218','partial','blank','erased-only','erased-previous','two-marks']
    colors=[(25,65,150),(240,195,10),(125,45,145),(45,45,45),(15,110,70)]
    for count in (40,75,100):
        supplier=json.loads((ROOT/f'qa/{40 if count==40 else 100}q-supplied-map-1.1.26.json').read_text())
        blank=Image.open(ROOT/f'output/random-1.1.27/blank-{count}.png').convert('RGB')
        for pattern_index,pattern in enumerate(patterns):
            rng=random.Random(a.seed+count*100+pattern_index)
            im=blank.copy();draw=ImageDraw.Draw(im);expected=[]
            for question in supplier['questions'][:count]:
                n=question['number'];choice=rng.randint(1,4)
                empty=pattern in ('blank','erased-only') or rng.random()<.2
                expected.append(None if empty else choice)
                def mark(option,ink,rx=.79,ry=.79):
                    point=question['options'][option-1];x,y=point['center_mm'];r=point['radius_mm']
                    draw.ellipse(((x-r*rx)*sx,(y-r*ry)*sy,(x+r*rx)*sx,(y+r*ry)*sy),fill=ink)
                if pattern=='erased-only':mark(choice,(232,232,232));continue
                if empty:continue
                ink=(int(pattern.split('-')[1]),)*3 if pattern.startswith('graphite-') else rng.choice(colors)
                mark(choice,ink,ry=.42 if pattern=='partial' else .79)
                if pattern=='erased-previous':mark(choice%4+1,(232,232,232))
                if pattern=='two-marks':mark(choice%4+1,rng.choice(colors))
            qr_text=f'{"T3" if count==40 else "T1"}:{count}{a.seed}:H{pattern_index:07d}'
            qr=qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M,box_size=6,border=4)
            qr.add_data(qr_text);qr.make(fit=True)
            box=supplier['blank_qr_placeholder_mm']
            im.paste(qr.make_image().convert('RGB').resize((round(box['w']*sx),round(box['h']*sy)),Image.Resampling.NEAREST),(round(box['x']*sx),round(box['y']*sy)))
            for effect in ('clear','shade','soft-noise','perspective','warm-light'):
                pixels=np.asarray(im).astype(np.float32)
                if effect=='shade':pixels*=np.linspace(.52,.96,pixels.shape[1])[None,:,None]
                if effect=='warm-light':pixels*=np.float32([.97,.84,.67])[None,None,:]
                if effect=='soft-noise':
                    pixels=cv2.GaussianBlur(pixels,(0,0),.65)
                    pixels+=np.random.default_rng(a.seed+count+pattern_index).normal(0,2.3,pixels.shape[:2])[:,:,None]
                if effect=='perspective':
                    h,w=pixels.shape[:2]
                    matrix=cv2.getPerspectiveTransform(np.float32([[0,0],[w-1,0],[w-1,h-1],[0,h-1]]),np.float32([[170,220],[w+210,300],[w+80,h+260],[250,h+110]]))
                    pixels=cv2.warpPerspective(pixels,matrix,(w+400,h+450),borderValue=(62,66,70))
                image=Image.fromarray(np.uint8(np.clip(pixels,0,255)))
                if effect in ('shade','soft-noise'):image=image.rotate(180 if effect=='shade' else 90,expand=True)
                name=f'holdout-{count}-{pattern}-{effect}';path=(a.output/(name+'.jpg')).resolve();image.save(path,quality=93)
                cases.append(dict(name=name,path=str(path),count=count,pattern=pattern,effect=effect,expected=expected,qr=qr_text,sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
        print(f'Generated {count}Q holdout',flush=True)
    (a.output/'manifest.json').write_text(json.dumps(dict(seed=a.seed,cases=cases),indent=2)+'\n')

if __name__=='__main__':main()
