#!/usr/bin/env python3
"""Generate and exercise 50 independently random, colored photos per 40/75/100Q print.

Expected answers and grading keys come from a seeded RNG and supplier coordinates,
never from the native result. Every original, normalized and graded image is retained.
"""
import argparse
import ctypes
import hashlib
import json
from pathlib import Path
import random
import subprocess
import time

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFilter
import qrcode

ROOT = Path(__file__).resolve().parents[1]
PALETTE = {'magenta':'#b5004d', 'red':'#c62828', 'orange':'#ef6c00', 'yellow':'#ffc107',
           'green':'#208038', 'teal':'#008577', 'blue':'#1565c0', 'purple':'#7b1fa2',
           'brown':'#795548', 'black':'#222222'}
SX, SY = 1750/148, 2480/210


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def rgb(value):
    return tuple(int(value[i:i+2], 16) for i in (1, 3, 5))


def generate(folder, seed):
    cases = []
    colors = list(PALETTE)
    for count in (40, 75, 100):
        supplier = json.loads((ROOT/f'qa/{40 if count==40 else 100}q-supplied-map-1.1.26.json').read_text())
        pdf = ROOT/f'app/src/main/assets/sheets/roshd-{count}q-A5.pdf'
        prefix = folder/f'blank-{count}'
        subprocess.run(['pdftoppm','-png','-singlefile','-scale-to-x','1750','-scale-to-y','2480',str(pdf),str(prefix)], check=True)
        original = Image.open(str(prefix)+'.png').convert('RGB')
        pixels = np.asarray(original).astype(np.float32)
        coverage = 1-np.min(pixels, axis=2, keepdims=True)/255
        pink = (pixels[:,:,0]>pixels[:,:,1]+2)&(pixels[:,:,2]>pixels[:,:,1]+2)
        for index in range(50):
            case_seed = seed+count*1000+index
            rng = random.Random(case_seed)
            ink_name = colors[index%10]
            pen_name = colors[(index//5+count)%10]
            ink = np.array(rgb(PALETTE[ink_name]), dtype=np.float32)
            replacement = 255*(1-coverage)+ink[None,None,:]*coverage
            colored = replacement if index%2 else np.where(pink[:,:,None], replacement, pixels)
            image = Image.fromarray(np.clip(colored, 0, 255).astype('uint8'))
            # Only registration is always black, including on all-content recolors.
            for anchor in supplier['corner_anchor_rectangles_mm']:
                bounds = (round((anchor['x']-.5)*SX), round((anchor['y']-.5)*SY),
                          round((anchor['x']+anchor['w']+.5)*SX), round((anchor['y']+anchor['h']+.5)*SY))
                image.paste(original.crop(bounds), bounds[:2])
            for dot in supplier['row_marks']:
                x,y = dot['center_mm']; r = dot['radius_mm']+.5
                bounds = (round((x-r)*SX),round((y-r)*SY),round((x+r)*SX),round((y+r)*SY))
                image.paste(original.crop(bounds), bounds[:2])
            family = 'T3' if count==40 else 'T1'
            payload = f'{family}:{count*1000+index}:R{index:07d}'
            qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=6, border=4)
            qr.add_data(payload); qr.make(fit=True)
            qr_image = qr.make_image(fill_color=rgb(PALETTE[ink_name]) if index%5==0 else 'black', back_color='white').convert('RGB')
            box = supplier['blank_qr_placeholder_mm']
            qr_image = qr_image.resize((round(box['w']*SX),round(box['h']*SY)),Image.Resampling.NEAREST)
            image.paste(qr_image,(round(box['x']*SX),round(box['y']*SY)))
            draw = ImageDraw.Draw(image)
            answers = [rng.randint(1,4) for _ in range(count)]
            key = [rng.randint(1,4) for _ in range(count)]
            for question in supplier['questions']:
                number = question['number']
                if number>count and (count!=75 or index%5!=0):
                    continue
                # Several 75Q pages contain deliberately filled inactive cells.
                choice = answers[number-1] if number<=count else rng.randint(1,4)
                option = question['options'][choice-1]
                cx,cy = option['center_mm']
                radius = option['radius_mm']*rng.uniform(.77,.85)
                cx += rng.uniform(-.035,.035)*option['radius_mm']
                cy += rng.uniform(-.035,.035)*option['radius_mm']
                name = rng.choice(colors) if index%5==4 else pen_name
                pen = rgb(PALETTE[name])
                draw.ellipse(((cx-radius)*SX,(cy-radius)*SY,(cx+radius)*SX,(cy+radius)*SY),fill=pen)
            rotation = 90*(index%4)
            if index%5:
                width,height = image.size
                source = np.float32([[0,0],[width,0],[width,height],[0,height]])
                corners = np.float32([[rng.uniform(45,100),rng.uniform(35,100)],
                                     [width-rng.uniform(25,80),rng.uniform(30,120)],
                                     [width-rng.uniform(25,85),height-rng.uniform(25,90)],
                                     [rng.uniform(30,85),height-rng.uniform(25,80)]])
                transform = cv2.getPerspectiveTransform(source,corners)
                warped = cv2.warpPerspective(np.array(image),transform,(width,height),borderValue=(65,70,75)).astype(np.float32)
                warped *= np.linspace(rng.uniform(.68,.88),1.0,width)[None,:,None]
                image = Image.fromarray(np.clip(warped,0,255).astype('uint8')).filter(ImageFilter.GaussianBlur(.25))
            image = image.rotate(rotation,expand=True)
            name = f'{count}q-{index+1:02d}'
            path = folder/'images'/f'{name}.jpg'
            image.save(path,quality=95)
            cases.append({'name':name,'count':count,'family':family,'seed':case_seed,'path':str(path),
                          'printInk':ink_name,'penInk':'mixed' if index%5==4 else pen_name,
                          'rotation':rotation,'qr':payload,'answers':answers,'answerKey':key,
                          'expectedCorrect':sum(a==b for a,b in zip(answers,key)),
                          'inactiveMarks':count==75 and index%5==0,'sha256':digest(path)})
        print(f'Generated 50 random {count}Q photos.',flush=True)
    manifest = {'seed':seed,'palette':PALETTE,'cases':cases}
    (folder/'manifest.json').write_text(json.dumps(manifest,indent=2))
    return cases


def run(library, folder, cases, report):
    lib = ctypes.CDLL(str(library.resolve()))
    for name in ('omr_mobile_process_json','omr_mobile_annotate_json'):
        getattr(lib,name).argtypes = [ctypes.c_char_p]
        getattr(lib,name).restype = ctypes.c_void_p
    lib.omr_mobile_free_string.argtypes = [ctypes.c_void_p]
    lib.omr_mobile_detect_printed_anchors.argtypes = [ctypes.c_void_p,ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.c_char_p]
    lib.omr_mobile_detect_printed_anchors.restype = ctypes.c_void_p
    def call(function, request):
        pointer = function(json.dumps(request,ensure_ascii=False).encode())
        if not pointer: raise RuntimeError('null native result')
        try: return json.loads(ctypes.string_at(pointer))
        finally: lib.omr_mobile_free_string(pointer)
    templates = [ROOT/'app/src/main/assets/templates'/n for n in
                 ('yse_omr_a5_t1_100q_v2.json','yse_omr_a5_t3_40q_v1.json')]
    results = []
    for case in cases:
        count = case['count']
        start = time.monotonic()
        response = call(lib.omr_mobile_process_json,{
            'templateJsonPaths':[str(templates[1 if count==40 else 0])],
            'includeQuestions':True,'returnAnnotationGeometry':True,'returnNormalizedImages':True,
            'outputDirectory':str(folder/'normalized'),'maxInputDimension':2000,
            'pages':[{'pageNumber':1,'imagePath':case['path'],'activeQuestionCount':count}]})
        elapsed = (time.monotonic()-start)*1000
        errors = []
        sheets = response.get('sheets',[])
        sheet = sheets[0] if len(sheets)==1 else {}
        questions = sheet.get('questions',[])
        if not sheet.get('success') or sheet.get('activeQuestionCount')!=count or len(questions)!=count:
            errors.append('recognition/count: '+str(sheet.get('error',response.get('error'))))
        if sheet.get('barcode',{}).get('text')!=case['qr']:
            errors.append('QR changed or missing')
        if sheet.get('sheetType',{}).get('code')!=case['family']:
            errors.append('physical family changed')
        for number,(expected,actual) in enumerate(zip(case['answers'],questions),1):
            if actual.get('question')!=number or actual.get('status')!='MARKED' or actual.get('selectedOptionNumber')!=expected:
                errors.append(f'Q{number}: expected {expected}, actual {actual.get("status")} / {actual.get("selectedOptionNumber")}')
        geometry = sheet.get('annotationGeometry',{})
        if len(geometry.get('questions',[]))!=count:
            errors.append('inactive or missing annotation rows')
        # Native answer observations must produce the independently known score.
        correct = sum(q.get('status')=='MARKED' and q.get('selectedOptionNumber')==key
                      for q,key in zip(questions,case['answerKey']))
        if correct!=case['expectedCorrect']:
            errors.append('random answer-key score mismatch')
        gray = cv2.imread(case['path'],cv2.IMREAD_GRAYSCALE)
        gray = cv2.resize(gray,None,fx=960/max(gray.shape),fy=960/max(gray.shape),interpolation=cv2.INTER_AREA)
        gray = np.ascontiguousarray(gray)
        pointer = lib.omr_mobile_detect_printed_anchors(gray.ctypes.data,gray.shape[1],gray.shape[0],gray.size,
                                                      templates[1 if count==40 else 0].read_bytes())
        try: preview = json.loads(ctypes.string_at(pointer))
        finally: lib.omr_mobile_free_string(pointer)
        if not preview.get('found'): errors.append('live black guides not found')
        marks = []
        for row in geometry.get('questions',[]):
            number = row['questionNumber']
            if number>count:
                errors.append('inactive question in annotation')
                continue
            choice = case['answers'][number-1]
            point = next((p for p in row['options'] if p['option']==choice),None)
            if point:
                marks.append(dict(point,questionNumber=number,verdict='correct' if choice==case['answerKey'][number-1] else 'incorrect'))
        annotation = {}
        if not errors:
            target = folder/'graded'/f'{case["name"]}.jpg'
            target.unlink(missing_ok=True)
            annotation = call(lib.omr_mobile_annotate_json,{
                'imagePath':sheet['normalizedImagePath'],'outputPath':str(target),
                'width':geometry['width'],'height':geometry['height'],'questionCount':count,'marks':marks})
            if not annotation.get('success') or annotation.get('correctMarks')!=case['expectedCorrect'] or annotation.get('incorrectMarks')!=count-case['expectedCorrect']:
                errors.append('graded JPEG/color counts mismatch')
            if not target.is_file() or Image.open(target).size!=(1750,2480):
                errors.append('graded image missing/wrong size')
        if digest(Path(case['path']))!=case['sha256']:
            errors.append('original bytes changed')
        result = {'name':case['name'],'passed':not errors,'count':count,'printInk':case['printInk'],
                  'penInk':case['penInk'],'elapsedMs':round(elapsed,2),'expectedCorrect':case['expectedCorrect'],
                  'actualCorrect':correct,'errors':errors,'previewFound':preview.get('found',False),
                  'sourceUnchanged':True,'annotation':annotation}
        results.append(result)
        (folder/'results'/f'{case["name"]}.json').write_text(json.dumps(response,indent=2))
        print(f'{len(results)}/150 {"PASS" if not errors else "FAIL"} {case["name"]} '
              f'{correct}/{count} {elapsed:.0f} ms '+('; '.join(errors[:2])),flush=True)
        report.write_text(json.dumps({'platform':'Linux x86_64; real native C ABI; synthetic PDF photos, not physical camera',
                                     'total':len(results),'passed':sum(r['passed'] for r in results),
                                     'palette':PALETTE,'results':results},indent=2))
    return all(r['passed'] for r in results)


if __name__=='__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--library',type=Path,required=True)
    parser.add_argument('--folder',type=Path,default=ROOT/'output/random-1.1.27')
    parser.add_argument('--report',type=Path,default=ROOT/'qa/random-150-1.1.27.json')
    parser.add_argument('--seed',type=int,default=20261114)
    parser.add_argument('--run-only',action='store_true')
    args = parser.parse_args()
    for sub in ('images','graded','normalized','results'):
        (args.folder/sub).mkdir(parents=True,exist_ok=True)
    cases = json.loads((args.folder/'manifest.json').read_text())['cases'] if args.run_only else generate(args.folder,args.seed)
    raise SystemExit(0 if run(args.library,args.folder,cases,args.report) else 1)
