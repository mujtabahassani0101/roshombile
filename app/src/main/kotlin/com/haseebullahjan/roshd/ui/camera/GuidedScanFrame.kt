package com.haseebullahjan.roshd.ui.camera

import kotlin.math.*

/** A fixed A5 target in the upright, uncropped camera image, independent of detections. */
object ScanGuide {
    const val DEFAULT_TARGET_MM=22f
    /** The displayed window and its bounded sampling area use the same scale. */
    fun targetSizeMm(pageWidthPixels:Float,density:Float):Float {
        require(pageWidthPixels>0 && density>0)
        return (44*density*148/pageWidthPixels).coerceIn(DEFAULT_TARGET_MM,26f)
    }
    /** Centers of the 7 mm printed squares in the current 148 × 210 mm PDF. */
    fun anchorCenters(page:List<Float>):List<Pair<Float,Float>> {
        require(page.size==8)
        return listOf(9.5f/148 to 9.5f/210,138.5f/148 to 9.5f/210,
            138.5f/148 to 200.5f/210,9.5f/148 to 200.5f/210).map { (u,v) ->
            ((page[0]*(1-u)+page[2]*u)*(1-v)+(page[6]*(1-u)+page[4]*u)*v) to
                ((page[1]*(1-u)+page[3]*u)*(1-v)+(page[7]*(1-u)+page[5]*u)*v)
        }
    }
    fun corners(width:Int,height:Int):List<Float> {
        require(width>0 && height>0)
        val pageWidth=min(width*.90f,height*.90f*148f/210f)
        val pageHeight=pageWidth*210f/148f
        val x=(1-pageWidth/width)/2;val y=(1-pageHeight/height)/2
        return listOf(x,y,1-x,y,1-x,1-y,x,1-y)
    }
}

/** Bounded luminance checks inside four fixed windows; no page search or warp. */
object GuidedScanFrame {
    data class Quality(val sharpness:Double,val mean:Double,val contrast:Double,val fingerprint:PageFingerprint,
                       val alignedCorners:List<Boolean>,val edgeCoherence:Double,val noiseFraction:Double) {
        val aligned get()=alignedCorners.all { it }
    }
    fun analyze(frame:CameraFramePacker.Frame,rotation:Int,corners:List<Float>,targetSizeMm:Float=ScanGuide.DEFAULT_TARGET_MM):Quality {
        require(rotation in listOf(0,90,180,270) && corners.size==8)
        require(targetSizeMm in ScanGuide.DEFAULT_TARGET_MM..26f)
        fun pixel(x:Float,y:Float):Int {
            val sx=when(rotation) { 90->y;180->1-x;270->1-y;else->x }
            val sy=when(rotation) { 90->1-x;180->1-y;270->x;else->y }
            val ix=(sx*(frame.width-1)).roundToInt().coerceIn(2,frame.width-3)
            val iy=(sy*(frame.height-1)).roundToInt().coerceIn(2,frame.height-3)
            return iy*frame.width+ix
        }
        val left=corners[0];val top=corners[1];val w=corners[2]-left;val h=corners[7]-top
        var sum=0.0;var gradient=0.0;var wideGradient=0.0;var n=0
        val horizontalLap=DoubleArray(4);val verticalLap=DoubleArray(4)
        val horizontalEnergy=DoubleArray(4);val verticalEnergy=DoubleArray(4)
        val histogram=IntArray(256)
        val columns=FloatArray(64) { x -> left+w*(.08f+.84f*(x+.5f)/64) }
        val rows=FloatArray(96) { y -> top+h*(.30f+.40f*(y+.5f)/96) }
        // The central answer area excludes the header in both page orientations:
        // a sharp upside-down QR cannot hide blurred answer bubbles.
        // Work is bounded at 6,144 samples on every phone.
        for(y in 0 until 96)for(x in 0 until 64) {
            val p=pixel(columns[x],rows[y])
            val center=frame.bytes[p].toInt() and 255
            // The five overlapping 3×3 filters need only 21 distinct pixels.
            // All weighted sums are exact integers; the /16 values and quality
            // decisions remain identical to evaluating each filter separately.
            // pixel() already guarantees the two-pixel boundary needed here.
            fun at(offset:Int)=frame.bytes[p+offset].toInt() and 255
            val stride=frame.width
            val a=at(-stride-2);val b=at(-stride-1);val c=at(-stride);val d=at(-stride+1);val e=at(-stride+2)
            val f=at(-2);val g=at(-1);val i=at(1);val j=at(2)
            val k=at(stride-2);val l=at(stride-1);val m=at(stride);val o=at(stride+1);val q=at(stride+2)
            val farTop=at(-2*stride);val farBottom=at(2*stride)
            val upper=at(-2*stride-1)+2*farTop+at(-2*stride+1)
            val lower=at(2*stride-1)+2*farBottom+at(2*stride+1)
            val v0=a+2*f+k;val v1=b+2*g+l;val v2=c+2*center+m;val v3=d+2*i+o;val v4=e+2*j+q
            val filtered=(v1+2*v2+v3)/16.0
            val filteredLeft=(v0+2*v1+v2)/16.0;val filteredRight=(v2+2*v3+v4)/16.0
            val mid=g+2*center+i
            val filteredTop=(upper+2*(b+2*c+d)+mid)/16.0
            val filteredBottom=(mid+2*(l+2*m+o)+lower)/16.0
            val gx=i-g;val gy=m-c;val wx=j-f;val wy=farBottom-farTop
            gradient+=gx*gx+gy*gy;wideGradient+=wx*wx+wy*wy
            val region=(if(x<32)0 else 1)+(if(y<48)0 else 2)
            val hx=filteredRight-filteredLeft;val hy=filteredBottom-filteredTop
            val lx=filteredLeft+filteredRight-2*filtered;val ly=filteredTop+filteredBottom-2*filtered
            horizontalLap[region]+=lx*lx;verticalLap[region]+=ly*ly
            horizontalEnergy[region]+=hx*hx;verticalEnergy[region]+=hy*hy
            histogram[center]++;sum+=center;n++
        }
        fun percentile(target:Int):Int { var count=0;for(i in histogram.indices) { count+=histogram[i];if(count>=target)return i };return 255 }
        val contrast=(percentile(n*95/100)-percentile(n*5/100)).toDouble()
        val mean=sum/n
        // Relative edge width survives changes in exposure and print darkness.
        // Uncorrelated sensor noise has no coherent edge across two pixels.
        val energy=gradient/n
        val coherence=wideGradient/max(gradient,1.0)
        // Inspect only four stationary windows. A complete printed square may
        // sit off center inside each one; the guides never follow detections.
        val noise=IntArray(80);var noiseCount=0
        val inner=IntArray(25);val ring=IntArray(20);val localNoise=IntArray(20)
        val tolerance=targetSizeMm/2-5f // 3.5 mm half-square + 1.5 mm sampling slack
        // At most 2 mm between probes even in the widest window: diagonal
        // off-center squares must not fall into gaps between coarse samples.
        val offsets=floatArrayOf(0f,-.25f,.25f,-.5f,.5f,-.75f,.75f,-1f,1f)
        val aligned=ScanGuide.anchorCenters(corners).map { (cx,cy) ->
            fun position(dx:Float,dy:Float)=pixel(cx+w*dx/148,cy+h*dy/210)
            fun sample(dx:Float,dy:Float):Int=frame.bytes[position(dx,dy)].toInt() and 255
            fun matches(dx:Float,dy:Float):Boolean {
                // Cheap central probe avoids sorting patches with no dark ink.
                val center=sample(dx,dy)
                val a=sample(dx-5.8f,dy);val b=sample(dx+5.8f,dy)
                val c=sample(dx,dy-5.8f);val d=sample(dx,dy+5.8f)
                val paper=min(max(min(a,b),min(c,d)),min(max(a,b),max(c,d)))
                if(paper-center<30)return false
                var index=0
                for(y in -2..2)for(x in -2..2)inner[index++]=sample(dx+x*.6f,dy+y*.6f)
                fun outside(x:Float,y:Float) {
                    val p=position(dx+x,dy+y)
                    fun at(offset:Int)=frame.bytes[p+offset].toInt() and 255
                    ring[index]=at(0)
                    localNoise[index++]=abs(at(-1)+at(1)+at(-frame.width)+at(frame.width)-4*at(0))
                }
                index=0
                for(i in -2..2) {
                    outside(-5.8f,i*1.5f);outside(5.8f,i*1.5f)
                    outside(i*1.5f,-5.8f);outside(i*1.5f,5.8f)
                }
                inner.sort();ring.sort()
                if(ring[5]-inner[20]<24 || ring[10]-inner[12]<30)return false
                // A small registration dot or a filled answer circle is not a
                // 7 mm square. Require dark ink over its wider four arms too.
                var broadInk=0
                for(i in -1..1) {
                    val v=i*1.5f
                    if(ring[10]-sample(dx-2.3f,dy+v)>=24)broadInk++
                    if(ring[10]-sample(dx+2.3f,dy+v)>=24)broadInk++
                    if(ring[10]-sample(dx+v,dy-2.3f)>=24)broadInk++
                    if(ring[10]-sample(dx+v,dy+2.3f)>=24)broadInk++
                }
                if(broadInk<8)return false
                localNoise.copyInto(noise,noiseCount);noiseCount+=localNoise.size;return true
            }
            offsets.any { y -> offsets.any { x -> matches(x*tolerance,y*tolerance) } }
        }
        // Estimate the high-frequency noise floor on the unprinted rings,
        // otherwise a noisy blurred photograph can look sharper than a clean one.
        noise.sort(0,noiseCount)
        val noiseVariance=if(aligned.all { it })(noise[noiseCount/2]/.6745).pow(2) else 0.0
        // The weaker axis matters: horizontal camera shake must not be hidden
        // by the remaining sharp horizontal print edges. A 3×3 binomial filter
        // suppresses pixel noise before measuring edge width in both directions.
        val sharpness=if(mean<48 || energy<20 || coherence<1.35 || noiseVariance>energy)0.0 else (0..3).minOf { region ->
            val samples=n/4.0
            val hx=horizontalEnergy[region]/samples-noiseVariance*.01171875
            val hy=verticalEnergy[region]/samples-noiseVariance*.01171875
            val lx=(horizontalLap[region]/samples-noiseVariance*.00703125).coerceAtLeast(0.0)
            val ly=(verticalLap[region]/samples-noiseVariance*.00703125).coerceAtLeast(0.0)
            if(min(hx,hy)<4)0.0 else 1000*min(lx/hx,ly/hy)
        }
        return Quality(sharpness,mean,contrast,PageFingerprint.sample(frame.width,frame.height,frame.bytes,corners,rotation),aligned,coherence,noiseVariance/max(energy,1.0))
    }
}
