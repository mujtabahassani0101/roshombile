#!/usr/bin/env python3
"""Extract measured vector geometry from the supplied November 15 print revision.
Run with the bundled PDF runtime. Never derives fixture truth from OMR templates.
"""
from pathlib import Path
import argparse,collections,copy,hashlib,io,json,statistics,subprocess
import pdfplumber
from reportlab.pdfgen.canvas import Canvas
from reportlab.lib.utils import ImageReader
from PIL import Image,ImageDraw,ImageFont

ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('pdf',type=Path);p.add_argument('--output',type=Path,required=True)
a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
source_hash=hashlib.sha256(a.pdf.read_bytes()).hexdigest()
with pdfplumber.open(a.pdf) as doc:
 for index,count in enumerate((100,75,40)):
  page=doc.pages[index];sx,sy=1750/page.width,2480/page.height
  white=lambda x:x.get('non_stroking_color')==(1.,1.,1.) or x.get('non_stroking_color')==[1.,1.,1.]
  black=lambda x:max(x.get('non_stroking_color') or [1])==0
  bubbles=[r for r in page.curves if 160<r['top']<540 and white(r) and 9<r['width']<15]
  dots=[r for r in page.curves if 160<r['top']<540 and black(r) and 9<r['width']<15]
  anchors=[r for r in page.rects if 19<r['width']<21 and black(r)]
  assert len(bubbles)==count*4 and len(dots)==(40 if count==40 else 50) and len(anchors)==4
  xs=sorted(set(round(r['x0']+r['width']/2,4) for r in bubbles))
  rows=20 if count==40 else 25
  questions=[];blocks=[]
  for col in range(count//rows):
   colxs=xs[col*4:col*4+4]
   ys=sorted(r['top']+r['height']/2 for r in bubbles if abs(r['x0']+r['width']/2-colxs[0])<.01)
   start=count-col*rows-rows+1
   radius=statistics.mean(r['width']/2 for r in bubbles)
   normalized_radius=statistics.mean((r['width']*sx+r['height']*sy)/4 for r in bubbles)
   blocks.append(dict(id=f'q{start:03d}_{start+rows-1:03d}',questionStart=start,questionCount=rows,questionNumberSide='right',optionOrderByX=['4','3','2','1'],xByOption={str(4-i):round(x*sx,6) for i,x in enumerate(colxs)},yByRow=[round(y*sy,6) for y in ys],bubbleRadius=round(normalized_radius,6),bbox=dict(x=(colxs[0]-radius*1.5)*sx,y=(ys[0]-radius*1.5)*sy,width=(colxs[-1]-colxs[0]+radius*3)*sx,height=(ys[-1]-ys[0]+radius*3)*sy)))
   for row,y in enumerate(ys):
    questions.append(dict(number=start+row,options=[dict(value=4-i,center_pt=[x,y],center_mm=[x/page.width*148,y/page.height*210],radius_pt=radius) for i,x in enumerate(colxs)]))
  left=[r for r in dots if r['x0']<50];right=[r for r in dots if r['x0']>350]
  left.sort(key=lambda r:r['top']);right.sort(key=lambda r:r['top'])
  assert len(left)==rows and len(right)==rows
  mapdata=dict(sourcePdfSha256=source_hash,page=index+1,pagePt=[page.width,page.height],referenceSize=[1750,2480],canonicalMm=[148,210],questionCount=count,questions=sorted(questions,key=lambda q:q['number']),railsPt=dict(left=[[r['x0']+r['width']/2,r['top']+r['height']/2] for r in left],right=[[r['x0']+r['width']/2,r['top']+r['height']/2] for r in right]),anchorsPt=[dict(x=r['x0'],y=r['top'],width=r['width'],height=r['height']) for r in anchors],qrBoxPt=[45.5,43.5,77,77],qrWhitePt=[48.5,46,72,72])
  (a.output/f'{count}q-vector-map.json').write_text(json.dumps(mapdata,indent=2)+'\n')
  oldname=f'yse_omr_a5_t{3 if count==40 else 1}_{40 if count==40 else 100}q_v{1 if count==40 else 2}.json'
  tpl=json.loads((ROOT/'output/new-sheets-1.1.41/before/app/src/main/assets/templates'/oldname).read_text())
  tpl['description']=f'Supplied November 15 2026 colored {count}-question print. Measured individual vector bubble rows, printed labels, independent QR and square anchors.'
  tpl['geometryId']=f't{3 if count==40 else 1}-a5-{count}q-20261115'
  # The new 40/100 designs preserve the previous normalized geometry to
  # within 0.001 px. Keep its original precision and bounding boxes: changing
  # rounding alone can move a faint-ink boundary sample by one whole pixel.
  if count!=75:
   for old,new in zip(tpl['answers']['blocks'],blocks):
    assert old['questionStart']==new['questionStart']
    assert max(abs(old['xByOption'][k]-new['xByOption'][k]) for k in old['xByOption'])<.001
    assert max(abs(x-y) for x,y in zip(old['yByRow'],new['yByRow']))<.001
    assert abs(old['bubbleRadius']-new['bubbleRadius'])<.0001
  tpl['sheetType']['questionCount']=count;tpl['answers']['questionCount']=count
  if count==75:tpl['answers']['blocks']=blocks
  tpl['coordinateSystem']['pdfPage'].update(widthPt=page.width,heightPt=page.height)
  reg=tpl['liveDetection']['registration']
  if count==75:reg.update(leftRailX=(left[0]['x0']+left[0]['width']/2)*sx,rightRailX=(right[0]['x0']+right[0]['width']/2)*sx,dotRadius=statistics.mean((r['width']*sx+r['height']*sy)/4 for r in dots),rowY=[(r['top']+r['height']/2)*sy for r in left])
  tpl['cornerAnchors']=[dict(x=r['x0']*sx,y=r['top']*sy,width=r['width']*sx,height=r['height']*sy) for r in anchors]
  rect=lambda x,y,w,h:dict(x=x*sx,y=y*sy,width=w*sx,height=h*sy)
  tpl['examCode']['box']=rect(45.5,43.5,77,77);tpl['examCode']['qrCode']['roi']=rect(48.5,46,72,72)
  tpl['liveDetection']['regions']['examQr']=tpl['examCode']['qrCode']['roi']
  # Header layout and answer region are bounded independently of the copied QR.
  if count==75:tpl['liveDetection']['regions']['answers']=rect(44,160,page.width-88,380)
  tpl['debug']=dict(sourcePdfSha256=source_hash,sourcePage=index+1,extractionMethod='PDF vector circles and rectangles; independent map saved separately',sharedGeometryOriginalPrecision=count!=75,sharedGeometryMaxDifferencePx=.001 if count!=75 else None,detectedAnswerBubbles=count*4,detectedSideDotsTotal=rows*2,cornerAnchors=4)
  name='yse_omr_a5_t1_75q_20261115.json' if count==75 else oldname
  (ROOT/'app/src/main/assets/templates'/name).write_text(json.dumps(tpl,indent=2)+'\n')
  # Generic samples contain no embedded student QR/name from the source PDF.
  # Rasterize at the same reference size so no hidden personal vector/text
  # remains underneath an overlay in the packaged assets.
  prefix=ROOT/'tmp/pdfs/new-sheets'/f'generic-{count}'
  subprocess.run(['pdftoppm','-f',str(index+1),'-l',str(index+1),'-scale-to-x','1750','-scale-to-y','2480','-singlefile','-png',str(a.pdf),str(prefix)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
  im=Image.open(str(prefix)+'.png').convert('RGB');draw=ImageDraw.Draw(im)
  box=lambda x,y,w,h:(round(x*sx),round(y*sy),round((x+w)*sx),round((y+h)*sy))
  for x,y,w,h in [(80,12,235,25),(48.5,46,72,72),(139,51,228,53)]:draw.rectangle(box(x,y,w,h),fill='white')
  font=ImageFont.truetype(str(ROOT/'app/src/main/assets/fonts/Vazirmatn-Regular.ttf'),round(10*sx))
  small=ImageFont.truetype(str(ROOT/'app/src/main/assets/fonts/Vazirmatn-Regular.ttf'),round(8*sx))
  digits=str(count).translate(str.maketrans('0123456789','۰۱۲۳۴۵۶۷۸۹'))
  draw.text((210*sx,25*sy),f'رشد - پاسخ‌نامهٔ {digits} سؤالی',font=font,fill='#242424',anchor='mm',direction='rtl')
  draw.text((356*sx,65*sy),'نام: ....................       نام پدر: ....................',font=small,fill='#47414c',anchor='rm',direction='rtl')
  draw.text((356*sx,91*sy),'صنف: ..........       مکتب: ..............................',font=small,fill='#47414c',anchor='rm',direction='rtl')
  draw.text((84.5*sx,82*sy),'کد برگه',font=small,fill='#777777',anchor='mm',direction='rtl')
  im.save(str(prefix)+'.png')
  target=ROOT/f'app/src/main/assets/sheets/roshd-{count}q-A5.pdf'
  canvas=Canvas(str(target),pagesize=(page.width,page.height));canvas.setTitle(f'Roshd {count}Q - November 15 generic reference')
  canvas.drawImage(ImageReader(im),0,0,width=page.width,height=page.height);canvas.save()
  im.resize((875,1240),Image.Resampling.LANCZOS).save(ROOT/'app/src/main/assets/images'/('omr-75-preview.png' if count==75 else 'omr-t3-preview.png' if count==40 else 'omr-t1-preview.png'))
  print(count,len(bubbles),'measured bubbles',len(dots),'rails')
