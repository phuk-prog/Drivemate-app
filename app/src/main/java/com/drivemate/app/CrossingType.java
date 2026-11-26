package com.drivemate.app;
/** Explicit mapped types, not a legal classification inferred from markings. */
final class CrossingType {
    private CrossingType(){}
    static final String[] ICONS={"","camera","crossing","calming","signal","zebra","pelican","puffin","toucan","parallel","pegasus","signal_crossing"};
    static int kind(String ref,String legacy,String signals){
        String r=ref==null?"":ref,l=legacy==null?"":legacy,s=signals==null?"":signals;
        int x=named(r),y=named(l);
        if(x>0&&y>0&&x!=y)return 2;
        int n=x>0?x:r.isEmpty()?y:0;
        if((n==5||n==9)&&("yes".equals(s)||"traffic_signals".equals(l)))return 2;
        if((n==6||n==7||n==8||n==10)&&("no".equals(s)||"unmarked".equals(l)||"uncontrolled".equals(l)))return 2;
        return n>0?n:("yes".equals(s)||"traffic_signals".equals(l)?11:2);
    }
    private static int named(String s){for(int i=5;i<=10;i++)if(ICONS[i].equals(s))return i;return 0;}
    static String label(int kind){
        switch(kind){
            case 5:return "Mapped zebra crossing";case 6:return "Mapped pelican crossing";
            case 7:return "Mapped puffin crossing";case 8:return "Mapped toucan crossing";
            case 9:return "Mapped parallel crossing";case 10:return "Mapped pegasus crossing";
            case 11:return "Mapped signal-controlled crossing";default:return "Mapped crossing";
        }
    }
}
