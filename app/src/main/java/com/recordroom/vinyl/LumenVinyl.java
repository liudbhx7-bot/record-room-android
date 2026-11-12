package com.recordroom.vinyl;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;

/** Fixed optics of the 浮光 platter: spinning pigment, but a single stationary edge lamp. */
final class LumenVinyl {
    private static final Shader.TileMode CLAMP = Shader.TileMode.CLAMP;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private float x, y, r;
    private int tint;
    private boolean black, automatic;
    private Shader body, shadow, spill, ambient, groove, trackBands, transmitted, caustics, edgeRefraction, windowReflection, polishedLip, spindle;

    // Called when the view changes size, material, or cover. Nothing is allocated while spinning.
    void prepare(float cx, float cy, float radius, int color, boolean darkPressing, boolean coverTinted) {
        if (r == radius && x == cx && y == cy && tint == color && black == darkPressing && automatic == coverTinted) return;
        x=cx; y=cy; r=radius; tint=color; black=darkPressing; automatic=coverTinted;
        if (r<=0) return;
        float[] hsv=new float[3]; Color.colorToHSV(color,hsv);
        float hue=hsv[0], sat=Math.min(.94f, hsv[1]*1.27f);
        if (darkPressing && !coverTinted) sat*=.37f;
        // Pale covers make pearl-grey resin; a dark cover still has enough transmitted colour.
        int smoked=hsv(hue,sat*.66f,darkPressing?.066f:.095f);
        int resin=hsv(hue,sat*.78f,darkPressing?.10f:.21f);
        int edge=hsv(hue,sat*.75f,darkPressing?.14f:.25f);
        if (coverTinted) {
            // Even with the underlight off, room light picks out the playing surface.
            // Limit absorbed light in the mid-grooves; blue needs more value than
            // amber at the same luminance. Keep the centre and outer edge smoked.
            resin = reflectedResin(hue, sat, darkPressing ? .112f : .115f);
            smoked = mix(smoked, resin, .52f);
            edge = mix(edge, resin, .58f);
        }
        int lamp=hsv(hue, sat<.08f?sat:Math.min(.98f,sat*1.1f+.09f),1f);
        int light=mix(lamp,0xfffff2dc,.28f);
        int filament=mix(lamp,0xfffff8ec,.68f);

        shadow=radial(x+r*.01f,y+r*.05f,r*1.06f,
                new int[]{0xc2000000,0xc2000000,0x60000000,0x00000000},0,.88f,.949f,1);
        // The hidden source sits under the seven-to-eight o'clock edge, never all around the deck.
        spill=radial(x-r*.80f,y+r*.58f,r*.39f,
                new int[]{alpha(light,150),alpha(lamp,72),alpha(lamp,13),alpha(lamp,0)},0,.31f,.69f,1);
        body=radial(x,y,r,new int[]{smoked,smoked,resin,edge,mix(edge,0xff171517,.48f)},
                0,.48f,.78f,.986f,1);
        ambient=new SweepGradient(x,y,new int[]{0x55000000,0x15696564,0x36000000,0x05000000,
                0x304d494a,0x00000000,0x21000000,0x298e8a89,0x444e4d4d,0x28000000,0x55000000},
                new float[]{0,.13f,.19f,.29f,.405f,.49f,.725f,.84f,.865f,.928f,1});
        // A narrow window reflection runs across the upper-right shoulder of the pressing.
        // It reads silver across the grooves even when the user turns the coloured lamp off.
        Shader windowFan = new SweepGradient(x,y,
                new int[]{0x00d9d4d2,0x00d9d4d2,0x0cd9d4d2,0x38c6bfbd,
                        0x14c3bdb8,0x5edbd7d3,0x65e0dbd8,0x27cec9c4,0x08b9b5b2,
                        0x00b9b5b2,0x00d9d4d2},
                new float[]{0,.768f,.799f,.831f,.852f,.867f,.871f,.881f,.909f,.929f,1});
        Shader windowFalloff = radial(x,y,r,new int[]{0x00ffffff,0x12ffffff,0x85ffffff,
                0xbbffffff,0xfeffffff,0xe1ffffff,0x55ffffff},0,.455f,.57f,.71f,.88f,.98f,1);
        windowReflection = new ComposeShader(windowFan,windowFalloff,PorterDuff.Mode.DST_IN);
        // Variable-width near-hairline cuts are visible where the room light catches the groove.
        int lines=Math.max(32,Math.min(128,Math.round(r*.24f)));
        float pitch=r/lines;
        Shader microCuts=new RadialGradient(x,y,pitch,
                new int[]{0x00ffffff,0x00ffffff,0x22ffffff,0xafffffff,0x10ffffff,0x00ffffff},
                new float[]{0,.49f,.67f,.78f,.90f,1},Shader.TileMode.REPEAT);
        Shader grooveTint=new SweepGradient(x,y,
                new int[]{0x16363136,0x4e999292,0x14959096,alpha(light,101),alpha(light,162),
                        0x2f9c939b,0x1139343a,0x61959194,0x8fb4b0ac,0x16363136},
                new float[]{0,.115f,.23f,.36f,.418f,.47f,.65f,.80f,.867f,1});
        groove=new ComposeShader(grooveTint,microCuts,PorterDuff.Mode.DST_IN);
        trackBands=radial(x,y,r,new int[]{0,0,0x53050004,0x12000000,0,0,0x45010002,0x08000000,0,0,0x3f010002,0x12000000,0},
                0,.535f,.54f,.548f,.551f,.676f,.68f,.689f,.692f,.829f,.832f,.839f,1);
        // The glass transmits most light at grazing angles. Radial fluting dies away towards
        // the paper label. The two small angular peaks are different facets of the same lamp.
        Shader lightSector=new SweepGradient(x,y,
                new int[]{alpha(lamp,0),alpha(lamp,0),alpha(lamp,18),alpha(lamp,70),
                        alpha(lamp,144),alpha(light,247),alpha(lamp,155),alpha(lamp,115),
                        alpha(light,191),alpha(lamp,65),alpha(lamp,12),alpha(lamp,0),alpha(lamp,0)},
                new float[]{0,.255f,.29f,.335f,.363f,.395f,.41f,.421f,.439f,.463f,.51f,.56f,1});
        Shader taper=radial(x,y,r,
                new int[]{0x00ffffff,0x00ffffff,0x11ffffff,0x27ffffff,0x45ffffff,0x8cffffff,0xf1ffffff,0x9cffffff,0x32ffffff,0x00ffffff},
                0,.50f,.685f,.822f,.92f,.966f,.983f,.99f,.996f,1);
        transmitted=new ComposeShader(lightSector,taper,PorterDuff.Mode.DST_IN);
        // Grazing light catches two short facets just inside the lip; one is bright,
        // one diffuse. The grooves crossing these facets sell the depth of the resin.
        Shader facets=new SweepGradient(x,y,new int[]{alpha(lamp,0),alpha(lamp,0),alpha(lamp,30),
                alpha(light,182),alpha(filament,243),alpha(lamp,68),alpha(lamp,0),
                alpha(lamp,0),alpha(lamp,80),alpha(light,155),alpha(lamp,20),alpha(lamp,0),alpha(lamp,0)},
                new float[]{0,.348f,.363f,.379f,.384f,.392f,.402f,.416f,.425f,.431f,.442f,.457f,1});
        Shader depth=radial(x,y,r,new int[]{0x00ffffff,0x00ffffff,0x08ffffff,0x38ffffff,
                0x5affffff,0xa4ffffff,0x8affffff,0x65ffffff,0xf8ffffff,0x26ffffff},
                0,.57f,.67f,.77f,.84f,.89f,.922f,.95f,.982f,1);
        caustics=new ComposeShader(facets,depth,PorterDuff.Mode.DST_IN);
        // A continuous, softly fading refracted edge connects the two internal facets.
        Shader rimSector = new SweepGradient(x,y,new int[]{alpha(lamp,0),alpha(lamp,0),
                alpha(lamp,36),alpha(lamp,151),alpha(light,237),alpha(filament,255),
                alpha(light,255),alpha(lamp,177),alpha(lamp,57),alpha(lamp,0),alpha(lamp,0)},
                new float[]{0,.23f,.266f,.319f,.361f,.391f,.421f,.46f,.492f,.548f,1});
        Shader rolledEdge = radial(x,y,r,new int[]{0x00ffffff,0x00ffffff,0x0effffff,
                0x43ffffff,0x93ffffff,0xe3ffffff,0xfeffffff,0x27ffffff,0x00ffffff},
                0,.822f,.897f,.94f,.965f,.978f,.987f,.996f,1);
        edgeRefraction = new ComposeShader(rimSector,rolledEdge,PorterDuff.Mode.DST_IN);
        polishedLip=new SweepGradient(x,y,new int[]{0x267f7770,0x727c756e,0x39bca990,alpha(lamp,95),
                alpha(light,235),alpha(filament,255),alpha(light,179),alpha(lamp,51),0x39302b2c,0x865b5457,0x267f7770},
                new float[]{0,.105f,.22f,.30f,.366f,.401f,.445f,.52f,.61f,.838f,1});
        spindle=new LinearGradient(x-r*.029f,y-r*.033f,x+r*.025f,y+r*.041f,
                new int[]{0xff575553,0xffaaa9a6,0xffd1cfc8,0xff7c7872,0xff3b3939,0xff615f5e},
                new float[]{0,.29f,.39f,.54f,.82f,1},CLAMP);
    }

    void drawBase(Canvas c,float lighting) {
        paint.setStyle(Paint.Style.FILL);
        shader(shadow,255); c.drawCircle(x+r*.01f,y+r*.05f,r*1.06f,paint);
        if (lighting>0) { shader(spill,level(lighting)); c.drawCircle(x-r*.80f,y+r*.58f,r*.39f,paint); }
        shader(body,255); c.drawCircle(x,y,r,paint);
    }
    void drawSurface(Canvas c,Path ring,float lighting) {
        paint.setStyle(Paint.Style.FILL);
        shader(ambient,245); c.drawPath(ring,paint);
        shader(trackBands,205); c.drawPath(ring,paint);
        shader(windowReflection,255); c.drawPath(ring,paint);
        // Optical layers are painted after restoring the rotating canvas.
        if (lighting>0) {
            int intensity=level(lighting)*(black?45:100)/100;
            shader(transmitted,intensity); c.drawPath(ring,paint);
            shader(caustics,intensity); c.drawPath(ring,paint);
            shader(edgeRefraction,intensity); c.drawPath(ring,paint);
        }
        shader(groove,200); c.drawPath(ring,paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(.7f,r*.0026f));
        solid(0x5a191718); c.drawCircle(x,y,r*.999f,paint);
        solid(0x4f9c9389); c.drawCircle(x,y,r*.994f,paint);
        if (lighting>0) { shader(polishedLip,level(lighting)); c.drawCircle(x,y,r*.989f,paint); }
        paint.setStyle(Paint.Style.FILL);
        solid(0x830e0c0c); c.drawCircle(x+r*.004f,y+r*.008f,r*.044f,paint);
        solid(0xff494746); c.drawCircle(x,y,r*.043f,paint);
        shader(spindle,255); c.drawCircle(x,y,r*.0405f,paint);
        paint.setShader(null);
    }

    private void shader(Shader shader,int alpha){paint.setShader(shader);paint.setColor(Color.WHITE);paint.setAlpha(alpha);}
    private void solid(int argb){paint.setShader(null);paint.setColor(argb);}
    private static int reflectedResin(float hue, float sat, float luminance) {
        // Linear-light scaling preserves the ink's chromaticity, even for saturated blue.
        int ink = hsv(hue, sat * .65f, 1f);
        float exposure = luminance / Color.luminance(ink);
        return Color.rgb(reflectedChannel(Color.red(ink), exposure),
                reflectedChannel(Color.green(ink), exposure), reflectedChannel(Color.blue(ink), exposure));
    }
    private static int reflectedChannel(int channel, float exposure) {
        double srgb = channel / 255d;
        double linear = srgb <= .04045 ? srgb / 12.92 : Math.pow((srgb + .055) / 1.055, 2.4);
        linear = Math.min(1, linear * exposure);
        double output = linear <= .0031308 ? linear * 12.92 : 1.055 * Math.pow(linear, 1 / 2.4) - .055;
        return (int)Math.round(output * 255);
    }
    private static int level(float value){return Math.round(255*(1-(1-value)*(1-value)));}
    private static int hsv(float hue,float saturation,float value){return Color.HSVToColor(new float[]{hue,saturation,value});}
    private static int alpha(int rgb,int opacity){return rgb&0xffffff|opacity<<24;}
    private static int mix(int a,int b,float t){return Color.rgb(Math.round(Color.red(a)*(1-t)+Color.red(b)*t),Math.round(Color.green(a)*(1-t)+Color.green(b)*t),Math.round(Color.blue(a)*(1-t)+Color.blue(b)*t));}
    private static Shader radial(float cx,float cy,float radius,int[] colors,float... stops){return new RadialGradient(cx,cy,radius,colors,stops,CLAMP);}
}
