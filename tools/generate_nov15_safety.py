#!/usr/bin/env python3
"""Independent mirrored and EXIF-stored camera positives on the November 15 print."""
from pathlib import Path
import argparse,hashlib,json
from PIL import Image
import numpy as np
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
originals=json.loads(a.manifest.read_text())['cases'];cases=[]
for count in [40,75,100]:
 selected=[c for c in originals if c['count']==count and c['pattern']=='normal' and c['effect']=='clear' and c['rotationXYZ'] in [[0,0,0],[30,-30,23]]]
 for c in selected:
  im=Image.open(c['path']);w,h=im.size
  for label,operation,matrix in [('mirror-x',Image.Transpose.FLIP_LEFT_RIGHT,[[-1,0,w-1],[0,1,0],[0,0,1]]),('mirror-y',Image.Transpose.FLIP_TOP_BOTTOM,[[1,0,0],[0,-1,h-1],[0,0,1]]),('transpose',Image.Transpose.TRANSPOSE,[[0,1,0],[1,0,0],[0,0,1]])]:
   name=c['name']+'-'+label;path=(a.output/f'{name}.jpg').resolve();im.transpose(operation).save(path,quality=96)
   cases.append(dict(c,name=name,path=str(path),mmToImage=(np.array(matrix)@np.array(c['mmToImage'])).tolist(),sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
 c=selected[0];im=Image.open(c['path'])
 inverse={2:Image.Transpose.FLIP_LEFT_RIGHT,3:Image.Transpose.ROTATE_180,4:Image.Transpose.FLIP_TOP_BOTTOM,5:Image.Transpose.TRANSPOSE,6:Image.Transpose.ROTATE_90,7:Image.Transpose.TRANSVERSE,8:Image.Transpose.ROTATE_270}
 for orientation in range(1,9):
  stored=im.copy() if orientation==1 else im.transpose(inverse[orientation]);exif=stored.getexif();exif[274]=orientation
  name=f'nov15-exif-{count}-{orientation}';path=(a.output/f'{name}.jpg').resolve();stored.save(path,quality=96,exif=exif)
  cases.append(dict(c,name=name,path=str(path),exifOrientation=orientation,sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
(a.output/'manifest.json').write_text(json.dumps(dict(cases=cases),indent=2)+'\n');print(len(cases),'cases')
