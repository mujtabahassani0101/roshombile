// Apply the compiled application's actual parsing/review policy, not a Python imitation.
import java.io.*;
import java.util.*;
import org.json.*;
import com.haseebullahjan.roshd.core.*;

public class StressVerdict {
    public static void main(String[] args) throws Exception {
        BufferedReader input=new BufferedReader(new InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8));
        String line;
        while((line=input.readLine())!=null) {
            JSONObject request=new JSONObject(line),sheet=request.getJSONObject("sheet");
            int count=request.getInt("count");
            boolean geometry=CameraNormalizationPolicy.INSTANCE.complete(sheet,count);
            CameraNormalizationPolicy.INSTANCE.markGeometryForReview(sheet,count);
            List<JSONObject> answers=Collections.emptyList();
            try {answers=OmrResultParser.INSTANCE.observations(sheet,count);}catch(Exception ignored){}
            boolean identity=request.getString("qr").equals(OmrResultParser.INSTANCE.qr(sheet));
            boolean ready=sheet.optBoolean("success") && identity && SheetFamilies.INSTANCE.matchesSelection(count,sheet.optJSONObject("sheetType").optString("code")) && answers.size()==count;
            int correct=0,review=0,unsafe=0,blankFalsePositive=0,missedMarks=0;
            JSONArray errors=new JSONArray(),expected=request.getJSONArray("expectedOptions");
            for(int i=0;i<count;i++) {
                JSONArray choices=expected.getJSONArray(i);JSONObject answer=i<answers.size()?answers.get(i):new JSONObject();
                String state=answer.optString("state"),wanted=choices.isEmpty()?"blank":choices.length()==1?"selected":"multiple";
                boolean flagged=ScanReview.INSTANCE.needsReview(answer);
                boolean ok=wanted.equals(state);
                if(ok && state.equals("selected"))ok=answer.optInt("selectedOption")==choices.getInt(0);
                if(ok && state.equals("multiple"))ok=answer.getJSONArray("markedOptions").toString().equals(choices.toString());
                if(ok)correct++;
                if(flagged)review++;
                if(!ok) {
                    if(ready && !flagged)unsafe++;
                    if(ready && choices.isEmpty() && state.equals("selected"))blankFalsePositive++;
                    if(ready && !choices.isEmpty() && state.equals("blank"))missedMarks++;
                    JSONObject raw=sheet.optJSONArray("questions")!=null && i<sheet.getJSONArray("questions").length()?sheet.getJSONArray("questions").getJSONObject(i):new JSONObject();
                    errors.put(new JSONObject().put("question",i+1).put("expected",choices).put("state",state).put("selected",answer.opt("selectedOption"))
                        .put("markedOptions",answer.optJSONArray("markedOptions")).put("review",flagged).put("nativeReview",raw.optBoolean("needsReview")).put("nativeReason",raw.optString("reviewReason")));
                }
            }
            JSONObject result=new JSONObject().put("ready",ready).put("geometry",geometry).put("identity",identity).put("correct",correct)
                .put("wrong",count-correct).put("review",review).put("unsafeWrong",unsafe).put("unsafeSheet",ready && review==0 && correct!=count)
                .put("blankFalsePositive",blankFalsePositive).put("missedMarks",missedMarks).put("errors",errors);
            if(request.optBoolean("includeAnswers"))result.put("answers",new JSONArray(answers));
            System.out.println(result);
            System.out.flush();
        }
    }
}
