#!/usr/bin/env python3
"""Raw preview admission fixtures: fixed windows, shifts, pose, blur and impostors.

Only local preview admission is tested, with no phone and no rectification.
Expected admission is authored independently of the Kotlin preview analyzer.
"""
import argparse,json
from pathlib import Path
from zipfile import ZipFile,ZIP_STORED
import cv2
import numpy as np

ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--source-dir',type=Path,help='Use source-{count}.png from a separate known-answer corpus')
p.add_argument('--output',type=Path,default=ROOT/'output/wide-guides-1.1.40/preview-fixtures')
p.add_argument('--archive',type=Path,default=ROOT/'app/src/test/resources/wide-guide-preview.zip')
a=p.parse_args();OUT=a.output
OUT.mkdir(parents=True,exist_ok=True)
cases=[];cv2.setNumThreads(2)
archive=ZipFile(a.archive,'w',compression=ZIP_STORED)
for count in (40,75,100):
    page=cv2.imread(str(a.source_dir/f'source-{count}.png' if a.source_dir else ROOT/f'app/src/androidTest/assets/preview/{count}q.jpg'),0)
    base=np.full((1280,960),70,np.uint8)
    base[64:1216,74:886]=cv2.resize(page,(812,1152),interpolation=cv2.INTER_AREA)
    sx,sy=812/148,1152/210
    variants={'clear':(base,True),'dim':(np.uint8(base*.48),True),
              'soft':(cv2.GaussianBlur(base,(0,0),.7),True)}
    for dx,dy in ((-6,0),(6,0),(0,-6),(0,6),(-4,-4),(4,4),(-4,4),(4,-4),(-15,0),(15,0),(0,-15),(0,15)):
        image=cv2.warpAffine(base,np.float32([[1,0,dx*sx],[0,1,dy*sy]]),(960,1280),borderValue=70)
        variants[f'shift-{dx}-{dy}']=(image,abs(dx)<=6 and abs(dy)<=6)
    for angle in (-2,-1,1,2):
        matrix=cv2.getRotationMatrix2D((480,640),angle,1)
        variants[f'roll-{angle}']=(cv2.warpAffine(base,matrix,(960,1280),borderValue=70),True)
    for scale in (.96,1.04):
        matrix=cv2.getRotationMatrix2D((480,640),0,scale)
        variants[f'scale-{scale}']=(cv2.warpAffine(base,matrix,(960,1280),borderValue=70),True)
    variants['blur']=(cv2.GaussianBlur(base,(0,0),2.5),False)
    variants['motion-blur']=(cv2.filter2D(base,-1,np.ones((1,17),np.float32)/17),False)
    noise=np.random.default_rng(401115+count).normal(0,20,base.shape)
    variants['noisy-blur']=(np.uint8(np.clip(cv2.GaussianBlur(base,(0,0),2.5).astype(float)+noise,0,255)),False)
    variants['uniform']=(np.full(base.shape,235,np.uint8),False)
    variants['noise']=(np.uint8(np.clip(210+noise,0,255)),False)
    centers=[(round(74+x*sx),round(64+y*sy)) for x,y in ((9.5,9.5),(138.5,9.5),(138.5,200.5),(9.5,200.5))]
    for index in range(4):
        im=base.copy();x,y=centers[index];im[y-65:y+65,x-65:x+65]=235
        variants[f'missing-{index}']=(im,False)
    # Keep sharp real answer detail, replacing only the square neighborhoods.
    for label in ('circles','outlines','qr-finders','bars'):
        im=base.copy()
        for x,y in centers:
            im[y-65:y+65,x-65:x+65]=235
            if label=='circles':cv2.circle(im,(x,y),round(2.3*sx),20,-1)
            elif label=='outlines':cv2.rectangle(im,(x-19,y-19),(x+19,y+19),20,3)
            elif label=='bars':cv2.rectangle(im,(x-25,y-5),(x+25,y+5),20,-1)
            else:
                cv2.rectangle(im,(x-19,y-19),(x+19,y+19),20,-1)
                cv2.rectangle(im,(x-13,y-13),(x+13,y+13),235,-1)
                cv2.rectangle(im,(x-8,y-8),(x+8,y+8),20,-1)
        variants[label]=(im,False)
    for label,(image,accepted) in variants.items():
        filename=f'{count}-{label}.png';path=OUT/filename;cv2.imwrite(str(path),image);archive.write(path,filename)
        for rotation in (0,90,180,270):
            for target in (22,26):cases.append(dict(file=filename,count=count,kind=label,rotation=rotation,targetMm=target,capture=accepted))
archive.writestr('manifest.json',json.dumps(cases));archive.close()
(OUT/'manifest.json').write_text(json.dumps(cases,indent=2)+'\n')
print(json.dumps(dict(cases=len(cases),positive=sum(c['capture'] for c in cases),negative=sum(not c['capture'] for c in cases))))
