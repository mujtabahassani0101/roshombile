// Replay the actual compiled preview classes, including an earlier release.
// Expected admissions come from the independent image manifest, never the class outputs.
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.json.*;

public class ReplayPreviewClasses {
  public static void main(String[] args) throws Exception {
    String pkg="com.haseebullahjan.roshd.ui.camera.";
    Class<?> frames=Class.forName(pkg+"CameraFramePacker$Frame"),analyzer=Class.forName(pkg+"GuidedScanFrame"),guides=Class.forName(pkg+"ScanGuide");
    var constructor=frames.getConstructor(int.class,int.class,byte[].class,byte[].class);
    var analyze=Arrays.stream(analyzer.getMethods()).filter(m->m.getName().equals("analyze")).findFirst().orElseThrow();
    Object reader=analyzer.getField("INSTANCE").get(null),guideObject=guides.getField("INSTANCE").get(null);
    JSONArray results=new JSONArray();String cached="";byte[] upright=null;int width=0,height=0;
    try(ZipFile zip=new ZipFile(args[0])) {
      JSONArray cases=new JSONArray(new String(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
      for(int i=0;i<cases.length();i++) {
        JSONObject c=cases.getJSONObject(i);String file=c.getString("file");
        if(!file.equals(cached)) {
          BufferedImage im=ImageIO.read(zip.getInputStream(zip.getEntry(file)));width=im.getWidth();height=im.getHeight();upright=new byte[width*height];
          for(int y=0;y<height;y++)for(int x=0;x<width;x++)upright[y*width+x]=(byte)im.getRaster().getSample(x,y,0);
          cached=file;
        }
        byte[] pixels=upright;int w=width,h=height;int rotation=c.getInt("rotation");
        for(int turn=0;turn<rotation/90;turn++) {
          byte[] rotated=new byte[pixels.length];for(int y=0;y<h;y++)for(int x=0;x<w;x++)rotated[(w-1-x)*h+y]=pixels[y*w+x];
          pixels=rotated;int old=w;w=h;h=old;
        }
        Object frame=constructor.newInstance(w,h,pixels,null);Object guide=guides.getMethod("corners",int.class,int.class).invoke(guideObject,width,height);
        long at=System.nanoTime();Object quality=analyze.getParameterCount()==4?analyze.invoke(reader,frame,rotation,guide,(float)c.getDouble("targetMm")):analyze.invoke(reader,frame,rotation,guide);
        double elapsed=(System.nanoTime()-at)/1e6;
        boolean aligned=(boolean)quality.getClass().getMethod("getAligned").invoke(quality);
        double sharpness=(double)quality.getClass().getMethod("getSharpness").invoke(quality);
        results.put(new JSONObject(c.toString()).put("admitted",aligned&&sharpness>=65).put("sharpness",sharpness).put("analysisMsHost",elapsed));
      }
    }
    Files.writeString(Path.of(args[1]),results.toString(2)+"\n");System.out.println("Replayed "+results.length()+" images with "+analyze.getParameterCount()+"-argument analyzer");
  }
}
