package com.drivemate.app;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.BackgroundColorSpan;
import java.util.regex.*;
/** Highlight provider-supplied road numbers only; never infer them from a street name. */
final class DrivingRoadLabel {
    private static final Pattern REF=Pattern.compile("(?<![A-Za-z0-9])(?:M[0-9]{1,3}|A[0-9]{1,4}|B[0-9]{1,4})(?![A-Za-z0-9])");
    static CharSequence highlight(String raw){
        String s=raw==null?"":raw;SpannableString out=new SpannableString(s);Matcher m=REF.matcher(s);
        while(m.find()){
            out.setSpan(new BackgroundColorSpan(m.group().startsWith("M")?0xff164d94:0xfff4f8fa),m.start(),m.end(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new ForegroundColorSpan(m.group().startsWith("M")?0xffffffff:0xff102e43),m.start(),m.end(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new StyleSpan(android.graphics.Typeface.BOLD),m.start(),m.end(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }return out;
    }
}
