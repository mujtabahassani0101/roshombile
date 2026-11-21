#!/usr/bin/env python3
"""Render the folder component's geometry for review without an Android emulator.

This is a static component preview, not an application screenshot.
"""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont,ImageFilter
ROOT=Path(__file__).resolve().parents[1];S=4
canvas=Image.new('RGBA',(400*S,352*S),'#F8F5ED')
draw=ImageDraw.Draw(canvas)
for x in range(0,400,20):draw.line((x*S,0,x*S,352*S),fill='#EEEAE2',width=2)
for y in range(0,352,20):draw.line((0,y*S,400*S,y*S),fill='#EEEAE2',width=2)
fontfile=ROOT/'app/src/main/assets/fonts/Vazirmatn-Bold.ttf'
regular=ROOT/'app/src/main/assets/fonts/Vazirmatn-Regular.ttf'
icons=ROOT/'app/src/main/assets/fonts/Phosphor-Regular.ttf'

def shape(left,top,w,h):
    r=8;rise=18;tab=w*.36;shoulder=tab+13;points=[];current=(r,0)
    def line(x,y):
        nonlocal current
        current=(x,y);points.append((left+x,top+y))
    def quad(cx,cy,x,y):
        nonlocal current
        sx,sy=current
        for i in range(1,21):
            t=i/20;u=1-t;points.append((left+u*u*sx+2*u*t*cx+t*t*x,top+u*u*sy+2*u*t*cy+t*t*y))
        current=(x,y)
    line(r,0);line(tab-r,0);quad(tab,0,tab+r*.5,r*.6)
    line(shoulder-r*.4,rise-r*.5);quad(shoulder,rise,shoulder+r*.5,rise)
    line(w-r,rise);quad(w,rise,w,rise+r);line(w,h-r);quad(w,h,w-r,h)
    line(r,h);quad(0,h,0,h-r);line(0,r);quad(0,0,r,0)
    return [(round(x*S),round(y*S)) for x,y in points]

def folder(x,y,color,title,detail,glyph,front_code,back_code):
    w=176;h=148
    backcolor=tuple(round(int(color[i:i+2],16)*.84) for i in (1,3,5))
    ImageDraw.Draw(canvas).polygon(shape(x+3,y+h-5-112,w-6,112),fill=backcolor)
    sheets=Image.new('RGBA',(118*S,130*S))
    def paper(code,px,py,angle):
        image=Image.open(ROOT/f'app/src/main/assets/images/omr-{code}-preview.png').convert('RGBA')
        sw=118*.60;sh=sw*image.height/image.width
        layer=Image.new('RGBA',sheets.size);layer.alpha_composite(image.resize((round(sw*S),round(sh*S)),Image.Resampling.LANCZOS),(round(px*S),round(py*S)))
        layer=layer.rotate(-angle,center=((px+sw*.5)*S,(py+sh*.75)*S),resample=Image.Resampling.BICUBIC)
        shadow=Image.new('RGBA',layer.size,(0,0,0,0));shadow.putalpha(layer.getchannel('A').point(lambda a:round(a*.16)).filter(ImageFilter.GaussianBlur(1.6*S)))
        sheets.alpha_composite(shadow,(0,S));sheets.alpha_composite(layer)
    paper(back_code,118*.17,130*.06,-13);paper(front_code,118*.34,130*.08,8)
    canvas.alpha_composite(sheets,(round((x+(w-118)/2)*S),round((y+1)*S)))
    d=ImageDraw.Draw(canvas);d.polygon(shape(x+3,y+h-5-100,w-6,100),fill=color)
    d.text(((x+w-17)*S,(y+90)*S),title,font=ImageFont.truetype(str(fontfile),15*S),fill='#211F1A',direction='rtl',anchor='rm')
    d.text(((x+w-17)*S,(y+120)*S),detail,font=ImageFont.truetype(str(regular),11*S),fill='#3B301E',direction='rtl',anchor='rm')
    d.text(((x+28)*S,(y+119)*S),chr(glyph),font=ImageFont.truetype(str(icons),21*S),fill='#211F1A',anchor='mm')

folder(24,21,'#6DAAF0','آرشیف','اصل عکس‌ها و فایل‌ها',0xe00c,'t1','t3')
folder(200,21,'#FFD731','امتحان‌ها','بسته‌های محفوظ',0xe23a,'t1','t3')
folder(24,177,'#BDA0DA','نتیجه‌ها','پاسخ‌نامه‌ها',0xe158,'t3','t1')
folder(200,177,'#FFA600','واردکردن','عکس و PDF',0xe836,'t3','t1')
output=ROOT/'output/roshd-folders-1.1.17-preview.png';canvas.convert('RGB').save(output,optimize=True)
print(output)
