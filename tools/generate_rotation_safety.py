#!/usr/bin/env python3
"""Derive mirror/EXIF positives and damaged/ambiguous registration negatives."""
import argparse
import hashlib
import json
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

ROOT=Path(__file__).resolve().parents[1]


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--manifest',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
    originals=json.loads(a.manifest.read_text())['cases'];positives=[];negatives=[]
    cv2.setNumThreads(2)
    for count in (40,75,100):
        selected=[c for c in originals if c['count']==count and c['effect']=='clear' and c['rotationXYZ'] in
                  ([0,0,0],[0,0,45],[-30,30,23])]
        for c in selected:
            im=Image.open(c['path']);w,h=im.size
            for label,operation,matrix in (
                ('mirror-x',Image.Transpose.FLIP_LEFT_RIGHT,[[-1,0,w-1],[0,1,0],[0,0,1]]),
                ('mirror-y',Image.Transpose.FLIP_TOP_BOTTOM,[[1,0,0],[0,-1,h-1],[0,0,1]]),
                ('transpose',Image.Transpose.TRANSPOSE,[[0,1,0],[1,0,0],[0,0,1]])):
                name=c['name']+'-'+label;path=(a.output/f'{name}.jpg').resolve();im.transpose(operation).save(path,quality=96)
                positives.append(dict(c,name=name,path=str(path),mmToImage=(np.array(matrix)@np.array(c['mmToImage'])).tolist(),
                                      reflection=label,sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
        c=next(c for c in selected if c['rotationXYZ']==[0,0,0]);im=Image.open(c['path'])
        inverse={2:Image.Transpose.FLIP_LEFT_RIGHT,3:Image.Transpose.ROTATE_180,4:Image.Transpose.FLIP_TOP_BOTTOM,
                 5:Image.Transpose.TRANSPOSE,6:Image.Transpose.ROTATE_90,7:Image.Transpose.TRANSVERSE,8:Image.Transpose.ROTATE_270}
        for orientation in range(1,9):
            stored=im.copy() if orientation==1 else im.transpose(inverse[orientation]);exif=stored.getexif();exif[274]=orientation
            name=f'exif-{count}-{orientation}';path=(a.output/f'{name}.jpg').resolve();stored.save(path,quality=96,exif=exif)
            positives.append(dict(c,name=name,path=str(path),exifOrientation=orientation,sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
        supplier=json.loads((ROOT/f'qa/{40 if count==40 else 100}q-supplied-map-1.1.26.json').read_text())
        plane=cv2.imread(str(a.manifest.parent/f'source-{count}.png'));sx,sy=1750/148,2480/210
        def rect(image,x,y,w,h,color):cv2.rectangle(image,(round(x*sx),round(y*sy)),(round((x+w)*sx),round((y+h)*sy)),color,-1)
        anchor=supplier['corner_anchor_rectangles_mm'][0]
        def remove_corner(image):rect(image,anchor['x']-.8,anchor['y']-.8,anchor['w']+1.6,anchor['h']+1.6,(255,255,255))
        variants={}
        missing=plane.copy();remove_corner(missing);variants['missing-square']=(missing,True,False)
        outline=missing.copy();x,y,w,h=[anchor[k] for k in ('x','y','w','h')]
        cv2.rectangle(outline,(round(x*sx),round(y*sy)),(round((x+w)*sx),round((y+h)*sy)),(0,0,0),3)
        variants['outline-square']=(outline,True,False)
        wrong=missing.copy();rect(wrong,x,y,w*.5,h*.5,(0,0,0));variants['wrong-size-square']=(wrong,True,False)
        displaced=missing.copy();rect(displaced,x+20,y+12,w,h,(0,0,0));variants['misplaced-square']=(displaced,True,False)
        no_rows=plane.copy()
        for mark in supplier['row_marks']:
            px,py=mark['center_mm'];r=mark['radius_mm']+1;rect(no_rows,px-r,py-r,r*2,r*2,(255,255,255))
        variants['missing-registration-rows']=(no_rows,False,True)
        no_qr=plane.copy();q=supplier['blank_qr_placeholder_mm'];rect(no_qr,q['x']-1,q['y']-1,q['w']+2,q['h']+2,(255,255,255))
        variants['missing-qr']=(no_qr,False,True)
        body=no_qr.copy();qr=plane[round(q['y']*sy):round((q['y']+q['h'])*sy),round(q['x']*sx):round((q['x']+q['w'])*sx)]
        yy,xx=round(120*sy),round(q['x']*sx);body[yy:yy+qr.shape[0],xx:xx+qr.shape[1]]=qr
        variants['body-qr']=(body,False,True)
        hmatrix=np.array(c['mmToImage'])@np.diag([148/1750,210/2480,1])
        for label,(image,reject_prepare,reject_complete) in variants.items():
            image=cv2.warpPerspective(image,hmatrix,(2400,2600),borderValue=(65,70,75))
            name=f'negative-{count}-{label}';path=(a.output/f'{name}.jpg').resolve();Image.fromarray(cv2.cvtColor(image,cv2.COLOR_BGR2RGB)).save(path,quality=95)
            negatives.append(dict(name=name,count=count,path=str(path),rejectPreparation=reject_prepare,rejectComplete=reject_complete,
                                  sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    (a.output/'positive-manifest.json').write_text(json.dumps(dict(cases=positives),indent=2)+'\n')
    (a.output/'negative-manifest.json').write_text(json.dumps(dict(cases=negatives),indent=2)+'\n')
    print(json.dumps(dict(positives=len(positives),negatives=len(negatives))))


if __name__=='__main__':main()
