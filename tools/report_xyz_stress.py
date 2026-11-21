#!/usr/bin/env python3
"""Publish a self-contained Persian report and exact, question-level Android audit."""
import argparse, base64, csv, hashlib, html, io, json, subprocess, zipfile
from datetime import datetime, timezone
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parents[1]
JAVA='/home/you/.local/share/mise/installs/java/temurin-21/bin/java'

def category(row):
    if not row['ready']:return 'retake'
    if row['unsafeWrong']:return 'error'
    if row['review']:return 'review'
    return 'auto'

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,required=True);a=p.parse_args();out=a.root.resolve()
    manifest=json.loads((out/'fixtures/manifest.json').read_text());truth={c['name']:c for c in manifest['cases']}
    android=json.loads((out/'android-merged.json').read_text())['checks'];native=json.loads((out/'final/report.json').read_text());baseline=json.loads((out/'baseline/report.json').read_text());holdout=json.loads((out/'final-holdout/report.json').read_text())
    assert len(android)==900 and len(truth)==900 and {r['name'] for r in android}==set(truth)
    host={c['name']:c for c in native['checks']};rows=[];question_rows=[];state_codes={'blank':0,'selected':1,'multiple':2,'ambiguous':3,'unreadable':4}
    verdict=subprocess.Popen([JAVA,'-Xmx192m','-cp',(out/'current-classpath.txt').read_text(),'StressVerdict'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
    def mask(values):return sum(1<<(int(v)-1) for v in set(values))
    try:
        for index,r in enumerate(android):
            c=truth[r['name']];label='a' if int(c['name'].split('-')[-1])%2==0 else 'b'
            sheet=json.loads((out/f'android-native-{label}'/f'{c["name"]}-result.json').read_text())
            request=dict(sheet=sheet,count=c['count'],qr=c['qr'],expectedOptions=c['expectedOptions'],includeAnswers=True)
            verdict.stdin.write(json.dumps(request,ensure_ascii=False)+'\n');verdict.stdin.flush();parsed=json.loads(verdict.stdout.readline())
            for key in ['ready','identity','geometry','correct','wrong','review','unsafeWrong']:assert parsed[key]==r[key],(c['name'],key,parsed[key],r[key])
            actual=[]
            for number,expected in enumerate(c['expectedOptions'],1):
                answer=parsed['answers'][number-1] if parsed['answers'] else dict(state='unreadable')
                state=answer['state'];options=answer.get('markedOptions',[]) if state=='multiple' else [answer['selectedOption']] if state=='selected' else []
                review=state not in ('blank','multiple','selected') or (state=='selected' and answer.get('needsReview',False))
                actual.append([state_codes[state],mask(options),int(review),answer.get('confidence'),answer.get('reviewReason','')])
                question_rows.append([c['name'],c['count'],number,','.join(map(str,expected)),state,','.join(map(str,options)),review,answer.get('confidence',''),answer.get('reviewReason',''),r['ready']])
            rows.append(dict(name=c['name'],count=c['count'],xyz=c['rotationXYZ'],pattern=c['pattern'],effect=c['effect'],qr=c['qr'],sha256=c['sha256'],
                category=category(r),review=r['review'],correct=r['correct'],wrong=r['wrong'],unsafe=r['unsafeWrong'],geometry=r['geometry'],
                elapsedMs=r['elapsedMs'],projectionError=host[c['name']]['projectionError'],expected=[mask(v) for v in c['expectedOptions']],actual=actual))
            if (index+1)%150==0:print(f'Validated Android and compiled policy: {index+1}/900',flush=True)
    finally:verdict.stdin.close();verdict.wait()
    rows.sort(key=lambda r:(r['count'],r['name']))
    units=json.loads((out/'unit-summary.json').read_text());counts={str(n):{k:sum(c['count']==n and c['category']==k for c in rows) for k in ['auto','review','retake','error']} for n in (40,75,100)}
    blank=[r for r in rows if r['pattern']=='blank']
    summary=dict(version='1.1.45',createdAt=datetime.now(timezone.utc).isoformat(),uniqueMainImages=900,imagesPerType=300,
        questionComparisons=64500,androidCases=900,byType=counts,unsafeWrong=sum(r['unsafe'] for r in rows),
        blankSheets=len(blank),blankSheetsAutomatic=sum(r['category']=='auto' for r in blank),
        holdout=dict(cases=holdout['cases'],questions=holdout['questions'],automatic=holdout['automaticExactSheets'],review=holdout['reviewSheets'],retake=holdout['rejectedSheets'],unsafeWrong=holdout['unsafeWrong']),
        before=dict(automatic=baseline['automaticExactSheets'],review=baseline['reviewSheets'],retake=baseline['rejectedSheets'],unsafeQuestionMismatches=baseline['unsafeWrong'],unsafeWholeSheets=baseline['unsafeSheets']),
        maxHostBubbleProjectionErrorPx=max(r['projectionError']['maxPx'] for r in rows if r['projectionError']),
        androidAnnotationGeometry=json.loads((out/'annotation-geometry.json').read_text()),
        printedLabelVisualCheck=dict(pages=3,questionLabels=215,optionLabels=860,matched=True),
        sourcePdfSha256=manifest['cases'][0]['sourcePdfSha256'],seed=manifest['seed'],unitTests=units,gradedUploadChecks=25,
        qualification='Synthetic projected photographs. 900 Android x86_64 emulator reads, plus independent native holdout. No physical camera, ARM64 runtime, or real server submission tested.')
    (out/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
    with (out/'question-results.csv').open('w',encoding='utf-8-sig',newline='') as f:
        writer=csv.writer(f);writer.writerow(['case','type','question','expected_options','actual_state','actual_options','needs_review','confidence','review_reason','sheet_ready']);writer.writerows(question_rows)
    with (out/'sheet-results.csv').open('w',encoding='utf-8-sig',newline='') as f:
        writer=csv.writer(f);writer.writerow(['case','type','X','Y','Z','ink','condition','outcome','correct','mismatch','review_count','unsafe_mismatch','android_ms','host_max_geometry_error_px','source_sha256'])
        for r in rows:writer.writerow([r['name'],r['count'],*r['xyz'],r['pattern'],r['effect'],r['category'],r['correct'],r['wrong'],r['review'],r['unsafe'],r['elapsedMs'],r['projectionError']['maxPx'] if r['projectionError'] else '',r['sha256']])
    for count in (40,75,100):
        cases=[c for c in manifest['cases'] if c['count']==count]
        with zipfile.ZipFile(out/f'stress-300-{count}q.zip','w',compression=zipfile.ZIP_STORED,allowZip64=True) as archive:
            for c in cases:archive.write(c['path'],Path(c['path']).name)
            archive.writestr('manifest.json',json.dumps(dict(cases=cases),ensure_ascii=False,indent=2),compress_type=zipfile.ZIP_DEFLATED)
            archive.write(cases[0]['vectorMap'],'pdf-vector-truth.json',compress_type=zipfile.ZIP_DEFLATED)
        print(f'Packaged {count}Q: 300 unchanged JPEGs',flush=True)
    by_name={r['name']:r for r in rows};sheet=Image.new('RGB',(1280,1134),'#f4f5f7');draw=ImageDraw.Draw(sheet)
    font=ImageFont.truetype(str(ROOT/'app/src/main/assets/fonts/Vazirmatn-Regular.ttf'),15)
    colors={'auto':'#147d45','review':'#b66a00','retake':'#b52b35','error':'#b52b35'}
    for y,count in enumerate((40,75,100)):
        for x,index in enumerate((0,1,44,{40:186,75:238,100:291}[count])):
            r=by_name[f'xyz-{count}-{index:03d}'];im=Image.open(truth[r['name']]['path']).convert('RGB');im.thumbnail((300,325),Image.Resampling.LANCZOS)
            left=x*320+10;top=y*378+8;sheet.paste(im,(left+(300-im.width)//2,top))
            draw.rectangle((left,top,left+300,top+325),outline=colors[r['category']],width=3)
            draw.text((left,top+330),f'{count}Q | X {r["xyz"][0]} Y {r["xyz"][1]} Z {r["xyz"][2]}',font=font,fill='#172433')
            draw.text((left,top+350),r['category'].upper()+' | '+r['name'],font=font,fill=colors[r['category']])
    sheet.save(out/'sample-scenes.jpg',quality=92)
    payload=dict(summary=summary,cases=rows)
    template='''<!doctype html><html lang="fa" dir="rtl"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>آزمون مو‌به‌موی رشد — ۹۰۰ برگه</title>
<style>body{font-family:Tahoma,Arial,sans-serif;background:#f4f5f7;color:#172433;margin:0;line-height:1.9}main{max-width:1250px;margin:auto;padding:24px}h1,h2{line-height:1.5}section{background:white;padding:22px;border-radius:14px;margin:16px 0}.cards{display:flex;flex-wrap:wrap;gap:12px}.card{flex:1;min-width:145px;background:white;padding:18px;border-radius:12px}.card b{display:block;font-size:28px;color:#176647}table{width:100%;border-collapse:collapse}td,th{padding:9px;text-align:right;border-bottom:1px solid #e2e6ea}th{background:#eef2f5;position:sticky;top:0}a{color:#116b94}button,select,input{font:inherit;padding:7px 12px;margin:3px;border:1px solid #bdc7d0;border-radius:7px;background:white}button{cursor:pointer}small{color:#566476}.scroll{max-height:640px;overflow:auto}.auto{color:#147d45}.review{color:#a55d00}.retake,.error{color:#b52b35}.samples{width:100%;height:auto}dialog{max-width:1120px;width:90%;max-height:90vh;border:0;border-radius:14px}dialog::backdrop{background:#172433aa}.cols{display:flex;gap:20px;align-items:flex-start}.cols img{max-width:32%;max-height:500px;object-fit:contain}.ltr{direction:ltr;unicode-bidi:isolate}code{direction:ltr;display:inline-block}p{margin:9px 0}@media(max-width:700px){main{padding:10px}section{padding:14px}.cols{display:block}.cols img{max-width:100%}td,th{font-size:12px;padding:5px}}</style>
<main><h1>آزمون مو‌به‌موی انجین رشد</h1><p>نسخهٔ اصلاح‌شدهٔ ۱.۱.۴۵ · ۳۰۰ تصویر برای هر نوع برگه · چرخش سه‌بعدی X و Y تا ±۶۰ درجه و Z در تمام جهت‌ها</p><div id="cards" class="cards"></div>
<section><h2>نتیجهٔ واقعی اجرای اندروید</h2><table><thead><tr><th>نوع برگه</th><th>تعداد</th><th>خوانش بدون بازبینی</th><th>نیازمند بازبینی</th><th>نیاز به عکس دوباره</th><th>خطای بدون هشدار</th></tr></thead><tbody id="totals"></tbody></table><p>«خوانش بدون بازبینی» دربارهٔ پردازش عکس محفوظ است؛ گرفتن خودکار عکس با دوربین زنده در این آزمون سنجیده نشده است.</p></section>
<section><h2>روش آزمون و مرز نتیجه‌ها</h2><p>هر ۹۰۰ تصویر در نسخهٔ واقعی اندروید، روی دو شبیه‌ساز x86_64 اجرا شد. انتظار هر سؤال از نقشهٔ برداریِ PDF اصلی و بذر ثابت ساخته شد؛ جواب خود انجین، مرجع آزمون نبود. ترتیب ۲۱۵ شمارهٔ سؤال و ۸۶۰ شمارهٔ گزینه نیز از تصویر خود PDF بصری کنترل شد. ۶۴٬۵۰۰ پاسخ، هویت QR، هندسه، سفید، تک‌علامتی و چندعلامتی بررسی شدند.</p><p>۶۳ برگه کاملاً سفید در مجموعهٔ اصلی وجود داشت. وضعیت آن‌ها در جدول سؤال‌به‌سؤال قابل مشاهده است. برای اثر پاک‌کن و علامت‌های ضعیف/ضربدری، بازبینی همچنان می‌تواند لازم باشد؛ این موارد قبولی خودکار حساب نشده‌اند.</p><p id="holdout"></p><p id="geometry"></p><p>در بررسی مراکز علامت‌گذاری اندروید نسبت به مختصات مرجع PDF، میانهٔ انحراف حدود ۰٫۰۸ پیکسل و بیشترین مقدار ۵٫۵۶ پیکسل بود. فقط چهار مرکز از ۲۵۷٬۵۴۰ مرکز از ۵ پیکسل بیشتر فاصله داشتند؛ هر چهار در سؤال ۲۱ یک برگهٔ ۴۰سؤالی با زاویهٔ X برابر با منفی ۶۰ درجه بودند. آن برگه بازبینی می‌خواست و قبولی خودکار حساب نشد.</p><p>آزمون دوربین فیزیکی، اجرای ARM64 روی گوشی و ارسال واقعی به سرور انجام نشده است. زمان هر تصویر فقط زمان آزمایشگاهیِ پردازش فایل در شبیه‌ساز است؛ زمان کل اسکن روی گوشی نیست.</p></section>
<section><h2>اشکال‌هایی که اصلاح شدند</h2><p>۱. هشدار معتبرِ جوهرِ ضعیف یا چند علامت دیگر در لایهٔ برنامه به سفیدِ بدون هشدار تبدیل نمی‌شود. سفید واقعی همچنان بازبینی نمی‌خواهد. اطلاعات تأییدشدهٔ دستی و نتیجه‌های امضاشده حفظ می‌شوند.</p><p>۲. چندعلامتیِ قطعی با تأیید چند خوانش و شاهد جوهر در تصویر اصلی، به صورت چندعلامتی ثبت می‌شود؛ انتخاب تک‌گزینه‌ایِ اولیه جای آن را نمی‌گیرد.</p><p>۳. شکل باریک‌شدهٔ نشانگر در زاویه‌های شدید، در مرحلهٔ پیشنهاد کنار گذاشته نمی‌شود. تأیید شکل چهار نشانگر و تمام ردیف‌های برگه پابرجاست. ۲۳ مورد از ۲۵ ردشدن اولیه پس از اصلاح خوانده شدند.</p><p>پیش از اصلاح، ۶۹۲ مغایرت در سطح سؤال، پرچم بازبینیِ همان سؤال را نداشتند. در این مجموعه، برگه‌های مربوط به آن‌ها به دلیل سؤال‌های دیگر بازبینی داشتند؛ این عدد به معنای ارسال ۶۹۲ پاسخ غلط به سرور نیست.</p></section>
<section><h2>نمونه‌های تصویری</h2><p>سبز: خوانش بدون بازبینی؛ زرد: تأیید لازم؛ سرخ: عکس دوباره. این‌ها عکس‌های مصنوعی از برگهٔ واقعی‌اند.</p><img class="samples" src="data:image/jpeg;base64,__SAMPLES__" alt="نمونه‌های برگه در زاویه‌های مختلف"></section>
<section><h2>پرونده‌های قابل بررسی</h2><p><a href="sheet-results.csv">خروجی هر ۹۰۰ برگه — CSV</a> · <a href="question-results.csv">خروجی تک‌تک ۶۴٬۵۰۰ سؤال — CSV</a> · <a href="summary.json">خلاصهٔ ماشینی آزمون</a></p><p><a href="stress-300-40q.zip">۳۰۰ برگهٔ ۴۰سؤالی — ZIP</a> · <a href="stress-300-75q.zip">۳۰۰ برگهٔ ۷۵سؤالی — ZIP</a> · <a href="stress-300-100q.zip">۳۰۰ برگهٔ ۱۰۰سؤالی — ZIP</a></p><p>آرشیوها شامل همان JPEGهای آزمایش‌شده و مرجع پاسخ‌ها هستند. تصاویر در بسته‌ها دوباره فشرده نشده‌اند. بذر تولید: <code>144900</code>؛ ترتیب چرخش: <code>Rz × Ry × Rx</code> با مبدأ مرکز صفحه.</p></section>
<section><h2>همهٔ برگه‌ها و سؤال‌ها</h2><div><input id="search" placeholder="جست‌وجوی شناسه"><select id="type"><option value="">همهٔ نوع‌ها</option><option>40</option><option>75</option><option>100</option></select><select id="outcome"><option value="">همهٔ نتیجه‌ها</option><option value="auto">بدون بازبینی</option><option value="review">بازبینی</option><option value="retake">عکس دوباره</option></select><span id="shown"></span></div><div class="scroll"><table><thead><tr><th>شناسه / جزئیات</th><th>نوع</th><th>X / Y / Z</th><th>قلم / شرایط</th><th>نتیجه</th><th>سؤال‌های بازبینی</th></tr></thead><tbody id="cases"></tbody></table></div></section>
<small>این گزارش نتایج مشاهده‌شده را بیان می‌کند؛ صفر بودن خطای بدون هشدار در این مجموعه، تضمین صفر خطا در عکس‌های واقعیِ دیده‌نشده نیست.</small></main>
<dialog id="detail"><button onclick="document.getElementById('detail').close()">بستن</button><div id="detailBody"></div></dialog>
<script>const DATA=__PAYLOAD__;const fa=n=>new Intl.NumberFormat('fa').format(n);const names={auto:'بدون بازبینی',review:'بازبینی لازم',retake:'عکس دوباره',error:'خطا'};const inks={mixed:'ترکیبی',blank:'سفید',black:'مشکی',blue:'آبی',green:'سبز',red:'سرخ',pencil:'مداد', 'light-pencil':'مداد کم‌رنگ',partial:'نیمه‌پر',cross:'ضربدر',multiple:'چندعلامتی',erased:'اثر پاک‌کن'};const effects={clear:'روشن',shadow:'سایه',dim:'کم‌نور',soft:'تاری',warm:'نور گرم',noise:'نویز',far:'فاصله',jpeg:'فشرده‌سازی'};const s=DATA.summary;const total=k=>Object.values(s.byType).reduce((v,r)=>v+r[k],0);document.getElementById('cards').innerHTML=[['تصویر اصلی',900],['بدون بازبینی',total('auto')],['بازبینی',total('review')],['عکس دوباره',total('retake')],['خطای بدون هشدار',s.unsafeWrong]].map(([label,n])=>`<div class="card"><b>${fa(n)}</b>${label}</div>`).join('');document.getElementById('totals').innerHTML=Object.entries(s.byType).map(([n,r])=>`<tr><td>${fa(n)}سؤالی</td><td>۳۰۰</td><td>${fa(r.auto)}</td><td>${fa(r.review)}</td><td>${fa(r.retake)}</td><td>${fa(r.error)}</td></tr>`).join('');document.getElementById('holdout').textContent=`کنترل مستقل: ${fa(s.holdout.cases)} تصویر تازه و ${fa(s.holdout.questions)} پاسخ، جدا از مجموعهٔ اصلی در انجین C++ و سیاست واقعی برنامه بررسی شدند؛ ${fa(s.holdout.automatic)} برگه بدون بازبینی، ${fa(s.holdout.review)} برگه با بازبینی و ${fa(s.holdout.retake)} برگه با نیاز به عکس دوباره؛ خطای بدون هشدار: ${fa(s.holdout.unsafeWrong)}.`;document.getElementById('geometry').textContent=`در آزمون مستقلِ کامپیوتر، بیشترین انحراف نگاشت مرکز حباب‌ها از مرجع PDF، ${s.maxHostBubbleProjectionErrorPx.toFixed(3)} پیکسل در تصویر صاف‌شدهٔ ۱۷۵۰×۲۴۸۰ بود. این عدد اندازه‌گیری هندسه در کامپیوتر است.`;
function render(){let q=document.getElementById('search').value.toLowerCase(),t=document.getElementById('type').value,o=document.getElementById('outcome').value;let rows=DATA.cases.filter(r=>(!q||r.name.toLowerCase().includes(q))&&(!t||r.count==t)&&(!o||r.category==o));document.getElementById('shown').textContent=fa(rows.length)+' برگه';document.getElementById('cases').innerHTML=rows.map(r=>`<tr><td><button class="ltr" onclick="show('${r.name}')">${r.name}</button></td><td>${fa(r.count)}</td><td class="ltr">${r.xyz.join(' / ')}</td><td>${inks[r.pattern]} / ${effects[r.effect]}</td><td class="${r.category}">${names[r.category]}</td><td>${fa(r.review)}</td></tr>`).join('')}
function options(mask){return [1,2,3,4].filter(n=>mask&(1<<(n-1))).map(fa).join('، ')}function expected(mask){return mask?options(mask):'سفید'}function actual(a){return a[0]===0?'سفید':a[0]===1?options(a[1]):a[0]===2?'چندعلامتی: '+options(a[1]):a[0]===3?'نامطمئن':'خوانده نشد'}function show(name){const r=DATA.cases.find(v=>v.name===name);const reasons={ink_visibility_recovered:'علامت از خوانش تکمیلی بازیابی شده',faint_ink_candidate:'علامت ضعیف',multiple_visible_marks:'چند علامتِ نامطمئن',ink_readings_disagree:'خوانش‌های ناسازگار'};let table=r.actual.map((a,i)=>{const match=(a[0]===0&&r.expected[i]===0)||((a[0]===1||a[0]===2)&&a[1]===r.expected[i]);let status=r.category==='retake'?'خوانش برگه کامل نیست':match?(a[2]?'مطابق مرجع؛ تأیید لازم':'مطابق مرجع'):(a[2]?'بازبینی لازم':'مغایرت بدون هشدار');return `<tr><td>${fa(i+1)}</td><td>${expected(r.expected[i])}</td><td>${actual(a)}</td><td>${a[3]==null?'—':fa(Math.round(a[3]*100))+'٪'}</td><td class="${a[2]?'review':match?'auto':'error'}">${status}${reasons[a[4]]?' · '+reasons[a[4]]:''}</td></tr>`}).join('');document.getElementById('detailBody').innerHTML=`<h2 class="ltr">${r.name}</h2><p>${names[r.category]} · ${inks[r.pattern]} · ${effects[r.effect]} · <span class="ltr">X/Y/Z: ${r.xyz.join(' / ')}</span></p><p><a target="_blank" href="fixtures/${r.count}/${r.name}.jpg">بازکردن تصویر اصلی</a></p><p><small>QR: <code>${r.qr}</code><br>SHA-256: <code>${r.sha256}</code></small></p><table><thead><tr><th>سؤال</th><th>مرجع</th><th>خوانش برنامه</th><th>اطمینان اعلام‌شده</th><th>وضعیت</th></tr></thead><tbody>${table}</tbody></table>`;document.getElementById('detail').showModal()}for(const id of ['search','type','outcome'])document.getElementById(id).addEventListener('input',render);render();</script></html>'''
    document=template.replace('__SAMPLES__',base64.b64encode((out/'sample-scenes.jpg').read_bytes()).decode()).replace('__PAYLOAD__',json.dumps(payload,ensure_ascii=False,separators=(',',':')).replace('<','\\u003c'))
    (out/'report.html').write_text(document)
    (out/'android-details.json').write_text(json.dumps(payload,ensure_ascii=False,separators=(',',':')))
    print(json.dumps(summary,ensure_ascii=False,indent=2),flush=True)

if __name__=='__main__':main()
