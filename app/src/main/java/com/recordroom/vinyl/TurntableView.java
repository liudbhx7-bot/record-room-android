package com.recordroom.vinyl;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import java.lang.ref.WeakReference;

/** Layered native turntable. Audio state is always supplied by PlayerService. */
public final class TurntableView extends View {
    private static final long REVOLUTION_MS = 10000L;
    private static final long ARM_TRANSITION_MS = 520L;
    private static final long RECORD_CONTACT_MS = 220L;
    private static final float RECORD_CONTACT_SCALE = 1.025f;
    private static final long RECORD_LIFT_MS = 360L, RECORD_FADE_OUT_MS = 160L,
            RECORD_FADE_IN_MS = 200L, RECORD_LOWER_MS = 360L;
    private static final float OUTER_GROOVE_ANGLE = 14f, INNER_GROOVE_ANGLE = 32f;
    private static final int ACCESSIBILITY_PLAY = 0x02010001, ACCESSIBILITY_PAUSE = 0x02010002;

    public interface NeedleListener {
        void onNeedleSeek(float progress);
        void onNeedlePlay();
        void onNeedlePause();
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint artworkPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint texturePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tonearmPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tonearmShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final FuguangDeck cabinet = new FuguangDeck();
    private int captionInsetPx;
    private final RectF oval = new RectF();
    private final RectF tonearmBounds = new RectF();
    private final Path arm = new Path(), platterRing = new Path();
    private final Matrix artMatrix = new Matrix(), materialMatrix = new Matrix();
    private final int touchSlop;
    private Bitmap recordTexture, artwork, pendingArtwork, tonearmTexture;
    private BitmapShader artShader, materialShader;
    private Shader armShader, mountShader;
    private final LumenVinyl resin = new LumenVinyl();
    private float cx, cy, radius, rotation, fraction, pivotX, pivotY;
    private float armAngle, armGoal, lighting = .7f, artworkAlpha = 1f, needlePreviewFraction;
    private float recordScale = 1f, recordScaleGoal = 1f;
    private int recordTint;
    private boolean recordTintEnabled;
    private int disc, materialGeneration, requestedMaterialSize = -1;
    private boolean playing, active, attached, motionEnabled = true;
    private boolean referenceLayout;
    private boolean artworkChanging, artworkSwitched, needleDropPreview;
    private ValueAnimator spinAnimator, armAnimator, artworkAnimator, recordScaleAnimator;
    private DiscMaterials.Request materialRequest;
    private ApprovedArtwork.Request tonearmRequest;
    private int tonearmGeneration, requestedTonearmEdge = -1;
    private NeedleListener needleListener;
    private float downX, downY, downPointerAngle, dragOriginAngle, dragAngle;
    private boolean touchCancelled, needleCandidate, needleDragging, needleSeekingEnabled = true;
    private int activePointer = -1;
    private final Runnable releaseNeedlePreview = () -> { needleDropPreview = false; updateAnimation(); };

    public TurntableView(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClickable(true); setFocusable(true);
        setContentDescription("黑胶唱机，拖动唱臂定位歌曲，抬针暂停，落针播放");
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        tonearmShadowPaint.setColorFilter(new PorterDuffColorFilter(0x28000000,PorterDuff.Mode.SRC_IN));
    }

    /** Full-screen portrait composition; compact/landscape callers retain the existing geometry. */
    public void setReferenceLayout(boolean enabled) {
        if (referenceLayout == enabled) return;
        referenceLayout = enabled;
        rebuild(); invalidate();
    }
    /** Actual lower-caption extent, including safe gap; only constrains portrait geometry. */
    public void setCaptionInsetPx(int px) {
        int next = Math.max(0,px);
        if (captionInsetPx == next) return;
        captionInsetPx=next;
        if (referenceLayout) {rebuild(); invalidate();}
    }
    /** Keep faceplate lettering readable when users select a light manual finish. */

    private float labelRatio() { return referenceLayout ? .455f : DesignSystem.RECORD_LABEL_RATIO; }
    public void setNeedleListener(NeedleListener listener) { needleListener = listener; }
    /** Disable needle manipulation when the current source cannot seek; ordinary clicks still work. */
    public void setNeedleSeekingEnabled(boolean enabled) {
        if (needleSeekingEnabled == enabled) return;
        needleSeekingEnabled = enabled;
        if (!enabled) {
            // Capability may disappear during a drag. Cancel without sending a final seek/play/pause.
            needleCandidate = false; needleDragging = false; needleDropPreview = false;
            touchCancelled = true; activePointer = -1; setPressed(false);
            removeCallbacks(releaseNeedlePreview);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            updateAnimation();
        }
        invalidate();
    }
    /** Changes only the vinyl material; zero restores its original colors for manual appearance. */
    public void setRecordTint(int color) {
        if (color == 0) {
            if (!recordTintEnabled) return;
            recordTintEnabled = false; recordTint = 0; texturePaint.setColorFilter(null);
            rebuild(); invalidate(); return;
        }
        int opaque = color | 0xff000000;
        if (recordTintEnabled && recordTint == opaque) return;
        recordTint = opaque; recordTintEnabled = true;
        texturePaint.setColorFilter(DiscMaterials.tintFilter(opaque));
        rebuild(); invalidate();
    }
    public void setMotionEnabled(boolean enabled) {
        if (motionEnabled != enabled) { motionEnabled = enabled; updateAnimation(); invalidate(); }
    }
    public void setLighting(float amount) {
        float value = Float.isNaN(amount) ? .7f : clamp(amount, 0, 1);
        if (lighting != value) { lighting = value; invalidate(); }
    }
    public void setStyle(int value) {
        int style = DiscMaterials.normalize(value);
        if (disc != style) {
            materialGeneration++;
            if (materialRequest != null) materialRequest.cancel();
            materialRequest = null;
            disc = style; recordTexture = null; materialShader = null; requestedMaterialSize = -1;
            rebuild(); invalidate();
        }
    }
    public void setArtwork(Bitmap value) {
        changeArtwork(value, false);
    }
    /** Metadata/artwork resolution updates on the same song must never lift the stylus. */
    public void updateArtworkInPlace(Bitmap value) {
        if(artworkChanging){
            pendingArtwork=value;
            // After the swap frame the animator no longer consumes pendingArtwork.
            // Refresh its visible image without restarting the lift/fade/lower timeline.
            if(artworkSwitched){replaceArtwork(value);invalidate();}
            return;
        }
        if(value==artwork)return;
        replaceArtwork(value);invalidate();
    }
    /** Track changes on the same record keep the sleeve in place; new artwork drives replacement. */
    public void beginRecordChange() { updateAnimation(); }
    private void changeArtwork(Bitmap value, boolean force) {
        if (value == artwork && !artworkChanging && !force) return;
        if (artworkChanging && value == pendingArtwork) return;
        finishArtworkTransition();
        if (value == null || artwork == null || !canAnimate() || needleDragging) {
            replaceArtwork(value); updateAnimation(); invalidate(); return;
        }
        // This is a visual lift only; changing a cover never pauses or starts the audio service.
        pendingArtwork = value; artworkChanging = true; artworkSwitched = false;
        stopArm(); stopSpin();
        final float initialArm = armAngle;
        final long fadeStart = RECORD_LIFT_MS;
        final long switchAt = fadeStart + RECORD_FADE_OUT_MS;
        final long lowerAt = switchAt + RECORD_FADE_IN_MS;
        final long total = lowerAt + RECORD_LOWER_MS;
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        artworkAnimator = animator;
        animator.setDuration(total); animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            float elapsed = (float) animation.getAnimatedValue() * total;
            if (elapsed >= switchAt && !artworkSwitched) {
                replaceArtwork(pendingArtwork); artworkSwitched = true; rotation = 0;
            }
            if (elapsed < fadeStart) {
                artworkAlpha = 1;
                armAngle = initialArm * (1 - smooth(elapsed / RECORD_LIFT_MS));
            } else if (elapsed < switchAt) {
                armAngle = 0; artworkAlpha = 1 - smooth((elapsed - fadeStart) / RECORD_FADE_OUT_MS);
            } else if (elapsed < lowerAt) {
                armAngle = 0; artworkAlpha = smooth((elapsed - switchAt) / RECORD_FADE_IN_MS);
            } else {
                artworkAlpha = 1;
                armAngle = (playing ? grooveAngle(fraction) : 0) * smooth((elapsed - lowerAt) / RECORD_LOWER_MS);
            }
            armGoal = armAngle;
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (artworkAnimator != animation) return;
                artworkAnimator = null;
                if (!artworkSwitched) replaceArtwork(pendingArtwork);
                pendingArtwork = null; artworkChanging = false; artworkAlpha = 1;
                updateAnimation(); invalidate();
            }
        });
        updateAnimation(); animator.start();
    }
    private void replaceArtwork(Bitmap value) {
        artwork = value;
        artShader = value == null ? null : new BitmapShader(value, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        placeArtwork();
    }
    private void finishArtworkTransition() {
        if (artworkAnimator != null) {
            ValueAnimator old = artworkAnimator; artworkAnimator = null; old.cancel();
        }
        if (artworkChanging && !artworkSwitched) replaceArtwork(pendingArtwork);
        pendingArtwork = null; artworkChanging = false; artworkAlpha = 1;
    }
    public void setPlaying(boolean value) {
        if (value && needleDropPreview) { needleDropPreview = false; removeCallbacks(releaseNeedlePreview); }
        if (playing != value) { playing = value; updateAnimation(); invalidate(); }
    }
    public void setActive(boolean value) {
        if (active != value) { active = value; updateAnimation(); }
    }
    /** A size-only rebuild keeps the same record pose; audio remains owned by the service. */
    public void restorePlaybackPoseFrom(TurntableView previous) {
        if(previous==null||previous==this)return;
        rotation=previous.rotation;fraction=previous.fraction;playing=previous.playing;
        armAngle=armGoal=playing?grooveAngle(fraction):0;
        recordScale=recordScaleGoal=previous.recordScale;
        updateAnimation();invalidate();
    }
    public void setProgress(float value) {
        fraction = Float.isNaN(value) ? 0 : clamp(value, 0, 1);
        if (!needleDragging && !artworkChanging && !needleDropPreview && armAnimator == null) {
            armAngle = playing ? grooveAngle(fraction) : 0; armGoal = armAngle;
        }
        if (spinAnimator == null) invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); attached = true; requestMaterial(); requestTonearm(); updateAnimation();
    }
    @Override protected void onDetachedFromWindow() {
        attached = false; needleCandidate = false; needleDragging = false; activePointer = -1;
        needleDropPreview = false; removeCallbacks(releaseNeedlePreview);
        materialGeneration++;
        if (materialRequest != null) materialRequest.cancel();
        materialRequest = null; requestedMaterialSize = -1;
        tonearmGeneration++;
        if(tonearmRequest!=null)tonearmRequest.cancel();
        tonearmRequest=null;requestedTonearmEdge=-1;
        updateAnimation(); super.onDetachedFromWindow();
    }
    @Override protected void onVisibilityChanged(View changed, int visibility) { super.onVisibilityChanged(changed, visibility); updateAnimation(); }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); updateAnimation(); }
    private boolean canAnimate() {
        return attached && active && isShown() && getWindowVisibility() == VISIBLE
                && motionEnabled && ValueAnimator.areAnimatorsEnabled();
    }
    private float armTarget() {
        return artworkChanging ? 0 : needleDropPreview ? grooveAngle(needlePreviewFraction) : playing ? grooveAngle(fraction) : 0;
    }
    private void updateAnimation() {
        boolean animate = canAnimate();
        if (!animate) {
            stopSpin(); stopArm(); finishArtworkTransition();
            if (!needleDragging) armAngle = armGoal = armTarget();
            updateRecordScale(false);
            invalidate(); return;
        }
        if (playing && !artworkChanging && spinAnimator == null) {
            spinAnimator = ValueAnimator.ofFloat(rotation, rotation + 360);
            spinAnimator.setDuration(REVOLUTION_MS);
            spinAnimator.setRepeatCount(ValueAnimator.INFINITE);
            spinAnimator.setInterpolator(new LinearInterpolator());
            spinAnimator.addUpdateListener(animation -> { rotation = ((float) animation.getAnimatedValue()) % 360; invalidate(); });
            spinAnimator.start();
        } else if (!playing || artworkChanging) stopSpin();
        updateRecordScale(true);
        if (needleDragging || artworkChanging) return;
        float goal = armTarget();
        if (Math.abs(goal - armGoal) > .03f || (armAnimator == null && Math.abs(armAngle - goal) > .03f)) {
            animateArmTo(goal);
        }
    }
    private void animateArmTo(float goal) {
        stopArm(); armGoal = goal;
        ValueAnimator animator = ValueAnimator.ofFloat(armAngle, goal);
        armAnimator = animator;
        animator.setDuration(ARM_TRANSITION_MS);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(animation -> { armAngle = (float) animation.getAnimatedValue(); updateRecordScale(canAnimate()); invalidate(); });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (armAnimator == animation) {
                    armAnimator = null;
                    if (!needleDragging) armAngle = armGoal = armTarget();
                    invalidate();
                }
            }
        });
        animator.start();
    }
    private void stopSpin() {
        if (spinAnimator != null) { ValueAnimator old = spinAnimator; spinAnimator = null; old.cancel(); }
    }
    private void stopArm() {
        if (armAnimator != null) { ValueAnimator old = armAnimator; armAnimator = null; old.cancel(); }
    }
    private void updateRecordScale(boolean animate) {
        // The plinth, arm and gesture coordinates stay fixed. Only a landed record breathes.
        float goal = !needleDragging && !artworkChanging && (needleDropPreview || playing && armAngle >= OUTER_GROOVE_ANGLE - 4)
                ? RECORD_CONTACT_SCALE : 1f;
        if (!animate) {
            stopRecordScale(); recordScale = recordScaleGoal = goal; return;
        }
        if (recordScaleGoal == goal && (recordScaleAnimator != null || Math.abs(recordScale - goal) < .0001f)) return;
        stopRecordScale(); recordScaleGoal = goal;
        ValueAnimator animator = ValueAnimator.ofFloat(recordScale, goal); recordScaleAnimator = animator;
        animator.setDuration(RECORD_CONTACT_MS); animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(animation -> { recordScale = (float) animation.getAnimatedValue(); invalidate(); });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (recordScaleAnimator == animation) { recordScaleAnimator = null; recordScale = recordScaleGoal; invalidate(); }
            }
        });
        animator.start();
    }
    private void stopRecordScale() {
        if (recordScaleAnimator != null) { ValueAnimator old = recordScaleAnimator; recordScaleAnimator = null; old.cancel(); }
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight); rebuild();
    }
    private void placeArtwork() {
        if (artShader == null || artwork == null || radius <= 0) { artworkPaint.setShader(null); return; }
        float size = radius * labelRatio() * 2;
        float scale = size / Math.min(artwork.getWidth(), artwork.getHeight());
        artMatrix.reset(); artMatrix.setScale(scale, scale);
        artMatrix.postTranslate(cx - artwork.getWidth() * scale / 2, cy - artwork.getHeight() * scale / 2);
        artShader.setLocalMatrix(artMatrix); artworkPaint.setShader(artShader);
    }
    private void placeMaterial() {
        if (materialShader != null && recordTexture != null && radius > 0) {
            DiscMaterials.place(recordTexture, materialShader, materialMatrix, cx, cy, radius);
            texturePaint.setShader(materialShader);
        } else texturePaint.setShader(null);
    }
    private void requestMaterial() {
        if (radius <= 0 || getWidth() <= 0 || getHeight() <= 0) return;
        int diameter = Math.max(1, (int) Math.ceil(radius * 2));
        int size = DiscMaterials.sizeForDiameter(diameter);
        if (requestedMaterialSize == size && (recordTexture != null || materialRequest != null)) return;
        requestedMaterialSize = size;
        final int token = ++materialGeneration;
        if (materialRequest != null) materialRequest.cancel();
        WeakReference<TurntableView> reference = new WeakReference<>(this);
        materialRequest = DiscMaterials.load(getContext(), disc, diameter, value -> {
            TurntableView view = reference.get();
            if (view == null || token != view.materialGeneration) return;
            view.materialRequest = null; view.recordTexture = value;
            view.materialShader = value == null ? null : new BitmapShader(value, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            view.placeMaterial(); view.invalidate();
        });
    }
    private void requestTonearm() {
        if(radius<=0)return;
        int edge=Math.max(128,Math.round(radius*2.03f));
        if(edge==requestedTonearmEdge&&(tonearmTexture!=null||tonearmRequest!=null))return;
        requestedTonearmEdge=edge;final int token=++tonearmGeneration;
        if(tonearmRequest!=null)tonearmRequest.cancel();
        WeakReference<TurntableView> owner=new WeakReference<>(this);
        tonearmRequest=ApprovedArtwork.load(getContext(),ApprovedArtwork.TONEARM,edge,bitmap->{TurntableView view=owner.get();if(view==null||token!=view.tonearmGeneration)return;view.tonearmRequest=null;view.tonearmTexture=bitmap;view.invalidate();});
    }
    private void rebuild() {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        if (referenceLayout) {
            // Width defines the record size on tall phones; extra height belongs to the
            // activity's track information and controls, not to a shrunken turntable.
            float scale = getWidth() / 443f;
            radius = Math.min(195f * scale, getHeight() * .44f);
            cx = getWidth() * (206.5f / 443f);
            // Centre the platter within the listening area, with room below for title and controls.
            cy = Math.max(radius + DesignSystem.dp(getContext(),28),
                    Math.min(getHeight() * .44f,getHeight()-radius-DesignSystem.dp(getContext(),158)));
            if(captionInsetPx>0) {
                float floor=Math.max(dp(18),getHeight()-captionInsetPx-dp(7));
                float overhead=Math.min(dp(77),floor*.26f);
                radius=Math.max(1,Math.min(radius,(floor-overhead)/2.05f));
                cy=Math.max(overhead+radius,Math.min(getHeight()*.44f,floor-radius*1.035f));
            }
            pivotX = cx + radius * (194.15f / 195f);
            pivotY = cy - radius * (146f / 195f);
        } else {
            // The wide layout has a separate transport column. Reserve space above the
            // deck for its stationary jacket instead of placing it behind a spinning disc.
            radius = Math.min(getWidth() * .425f, getHeight() * .345f);
            cx = getWidth() * .452f; cy = getHeight() * .59f;
            pivotX = cx + radius * 1.075f; pivotY = cy - radius * .88f;
        }
        cabinet.prepare(getWidth(),getHeight(),cx,cy,radius,RECORD_CONTACT_SCALE,getResources().getDisplayMetrics().density);
        resin.prepare(cx, cy, radius, recordTintEnabled ? recordTint : DiscMaterials.color(disc), disc == 0, recordTintEnabled);
        boolean darkArm = disc != 0;
        armShader = new LinearGradient(-radius * .018f, 0, radius * .022f, 0,
                darkArm ? new int[]{0xff080a09, 0xff292e2b, 0xff363b37, 0xff0b0d0b}
                        : new int[]{0xff555b5a, 0xffd0d4ce, 0xfff5f4e8, 0xff717775},
                new float[]{0, .32f, .63f, 1}, Shader.TileMode.CLAMP);
        mountShader = new LinearGradient(0, -radius * .1f, 0, radius * .11f,
                new int[]{0xff272b28, 0xff10120f, 0xff20231f}, null, Shader.TileMode.CLAMP);
        arm.reset(); arm.moveTo(0, radius * .055f); arm.lineTo(0, radius * 1.20f); arm.lineTo(-radius * .026f, radius * 1.26f);
        platterRing.reset(); platterRing.setFillType(Path.FillType.EVEN_ODD);
        platterRing.addCircle(cx, cy, radius, Path.Direction.CW); platterRing.addCircle(cx, cy, radius * (referenceLayout ? .46f : .425f), Path.Direction.CW);
        placeMaterial(); requestMaterial(); placeArtwork(); requestTonearm();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (radius <= 0) return;
        cabinet.draw(canvas,artwork);
        int scaledRecord = canvas.save(); canvas.scale(recordScale,recordScale,cx,cy);
        int recordLayer=-1;
        if(artworkChanging){recordLayer=canvas.saveLayerAlpha(cx-radius*1.12f,cy-radius*1.12f,cx+radius*1.12f,cy+radius*1.18f,Math.round(artworkAlpha*255));float scale=.97f+.03f*artworkAlpha;canvas.scale(scale,scale,cx,cy);}
        resin.drawBase(canvas, lighting);
        canvas.save(); canvas.rotate(rotation + (referenceLayout ? -21.5f : 0), cx, cy);
        if (materialShader != null) {
            // Pigment rotates in the pressing; reflected light and the deck lamp do not.
            float pigment = recordTintEnabled ? (disc == 5 ? .08f : .29f) : .80f;
            texturePaint.setAlpha(Math.round(DiscMaterials.alpha(disc) * pigment));
            canvas.drawCircle(cx, cy, radius, texturePaint);
        }
        p.setShader(null); p.setStyle(Paint.Style.FILL);
        p.setColor(0xff201d1e); canvas.drawCircle(cx, cy, radius * (referenceLayout ? .46f : .425f), p);
        if (artShader != null) {
            artworkPaint.setAlpha(255);
            canvas.drawCircle(cx, cy, radius * labelRatio(), artworkPaint);
        } else {
            p.setColor(0xffd6a28d); canvas.drawCircle(cx, cy, radius * labelRatio(), p);
            p.setColor(0xff83778c); oval.set(cx - radius * .4f, cy - radius * .08f, cx + radius * .4f, cy + radius * .36f);
            canvas.drawArc(oval, 180, 180, true, p);
        }
        canvas.restore();
        resin.drawSurface(canvas, platterRing, lighting);
        if(recordLayer>=0)canvas.restoreToCount(recordLayer);
        canvas.restoreToCount(scaledRecord);
        drawArm(canvas);
    }
    private void drawArm(Canvas canvas) {
        if(tonearmTexture!=null&&!tonearmTexture.isRecycled()){
            // Approved source pivot is (50%,18%); the stylus is approximately y=96%.
            // Its length puts the 14–32 degree gesture range on the outer/inner grooves.
            float height=radius*(referenceLayout?372f/195f:1.58f/.78f),width=height*tonearmTexture.getWidth()/tonearmTexture.getHeight();
            float sourcePivot=referenceLayout?.1855f:.18f;
            tonearmBounds.set(-width*.5f,-height*sourcePivot,width*.5f,height*(1-sourcePivot));
            int save=canvas.save();canvas.translate(pivotX,pivotY);canvas.rotate(needleDragging?dragAngle:armAngle);
            int shadow=canvas.save();float lift=needleDragging?.025f:artworkChanging?.022f:.014f;
            canvas.translate(radius*lift,radius*(lift+.007f));canvas.drawBitmap(tonearmTexture,null,tonearmBounds,tonearmShadowPaint);canvas.restoreToCount(shadow);
            canvas.drawBitmap(tonearmTexture,null,tonearmBounds,tonearmPaint);canvas.restoreToCount(save);return;
        }
        float r = radius;
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(0x08706a65);
        oval.set(pivotX - r * .29f, pivotY - r * .22f, pivotX + r * .12f, pivotY + r * .40f);
        canvas.drawRoundRect(oval, r * .21f, r * .21f, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(.6f, r * .003f)); p.setColor(0x24796362);
        canvas.drawRoundRect(oval, r * .21f, r * .21f, p); p.setStyle(Paint.Style.FILL);
        // The fixed bearing belongs to the plinth; only the arm assembly rotates.
        p.setColor(0x15000000);canvas.drawCircle(pivotX+r*.008f,pivotY+r*.012f,r*.065f,p);
        p.setColor(0xff202421);canvas.drawCircle(pivotX,pivotY,r*.056f,p);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.max(.6f,r*.003f));p.setColor(0xff454a44);
        canvas.drawCircle(pivotX,pivotY,r*.049f,p);p.setStyle(Paint.Style.FILL);
        canvas.save(); canvas.translate(pivotX, pivotY); canvas.rotate(needleDragging ? dragAngle : armAngle);
        canvas.save();
        float lift = needleDragging ? .027f : artworkChanging ? .020f : .015f;
        canvas.translate(r * lift, r * (lift + .003f));
        p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(r * .053f); p.setColor(0x28000000); canvas.drawPath(arm, p); canvas.restore();
        p.setStrokeWidth(r * .042f); p.setAlpha(255); p.setShader(armShader); canvas.drawPath(arm, p); p.setShader(null); p.setStyle(Paint.Style.FILL);
        p.setColor(0xff121512); oval.set(-r * .183f, -r * .145f, -r * .055f, -r * .067f); canvas.drawRect(oval, p);
        p.setShader(mountShader); oval.set(-r * .20f, -r * .09f, r * .048f, r * .067f);
        canvas.drawRoundRect(oval, r * .009f, r * .009f, p); p.setShader(null);
        p.setColor(0xff242824); canvas.drawRect(-r * .189f, -r * .065f, -r * .027f, -r * .052f, p);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.max(.6f,r*.003f));p.setColor(0xff41453f);
        canvas.drawLine(-r*.18f,-r*.087f,r*.027f,-r*.087f,p);
        p.setStyle(Paint.Style.FILL);p.setColor(0xff111410);canvas.drawCircle(0,0,r*.025f,p);
        p.setColor(0xff383d36);canvas.drawCircle(-r*.003f,-r*.004f,r*.012f,p);
        p.setColor(0xff151913);p.setStrokeWidth(Math.max(.6f,r*.003f));canvas.drawLine(-r*.011f,-r*.004f,r*.005f,-r*.004f,p);
        p.setColor(0xff111510); canvas.drawRect(-r * .025f, r * 1.19f, r * .025f, r * 1.28f, p);
        canvas.save(); canvas.translate(-r * .026f, r * 1.27f); canvas.rotate(24);
        p.setColor(0xff101410); oval.set(-r * .048f, -r * .032f, r * .048f, r * .19f);
        canvas.drawRoundRect(oval, r * .014f, r * .014f, p);
        p.setColor(0xff222822); canvas.drawRect(-r * .037f, -r * .021f, -r * .026f, r * .18f, p);
        p.setColor(0xff30362e);canvas.drawRect(-r*.025f,-r*.023f,r*.034f,-r*.013f,p);
        p.setColor(0xff181e17);canvas.drawRect(r*.027f,0,r*.037f,r*.17f,p);
        p.setStrokeWidth(r * .013f); p.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(r * .035f, r * .15f, r * .095f, r * .15f, p);
        p.setColor(0xff73694f);canvas.drawRect(-r*.014f,r*.178f,r*.014f,r*.193f,p);
        p.setColor(0xffc0b293); canvas.drawRect(-r * .004f, r * .19f, r * .004f, r * .208f, p);
        canvas.restore(); canvas.restore();
    }

    private boolean hitsArm(float x, float y) {
        if (radius <= 0) return false;
        double radians = Math.toRadians(armAngle);
        float dx = x - pivotX, dy = y - pivotY;
        float localX = (float) (dx * Math.cos(radians) + dy * Math.sin(radians));
        float localY = (float) (-dx * Math.sin(radians) + dy * Math.cos(radians));
        float target = Math.max(dp(24), radius * .085f);
        return localY >= radius * .22f && localY <= radius * 1.68f
                && localX >= -target - (localY > radius * 1.2f ? radius * .08f : 0) && localX <= target;
    }
    private float pointerAngle(float x, float y) { return (float) Math.toDegrees(Math.atan2(-(x - pivotX), y - pivotY)); }
    private static float grooveAngle(float progress) { return OUTER_GROOVE_ANGLE + progress * (INNER_GROOVE_ANGLE - OUTER_GROOVE_ANGLE); }
    private static float progressForAngle(float angle) { return clamp((angle - OUTER_GROOVE_ANGLE) / (INNER_GROOVE_ANGLE - OUTER_GROOVE_ANGLE), 0, 1); }
    private void startNeedleDrag() {
        needleDragging = true; needleDropPreview = false; removeCallbacks(releaseNeedlePreview);
        stopArm(); finishArtworkTransition(); setPressed(false);
        updateRecordScale(canAnimate());
        if (needleListener != null) needleListener.onNeedlePause();
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }
    private void moveNeedle(float x, float y) {
        float difference = pointerAngle(x, y) - downPointerAngle;
        while (difference > 180) difference -= 360;
        while (difference < -180) difference += 360;
        dragAngle = clamp(dragOriginAngle + difference, -4, 42); invalidate();
    }
    private void finishNeedleDrag(boolean commit) {
        float angle = dragAngle;
        needleDragging = false; needleCandidate = false; armAngle = angle;
        if (commit && angle >= OUTER_GROOVE_ANGLE - 4) {
            fraction = progressForAngle(angle);
            needlePreviewFraction = fraction;
            // Hold the drop pose briefly while Media3 prepares; the service still owns playback.
            needleDropPreview = true; removeCallbacks(releaseNeedlePreview); postDelayed(releaseNeedlePreview, 900L);
            if (needleListener != null) { needleListener.onNeedleSeek(fraction); needleListener.onNeedlePlay(); }
        } else {
            needleDropPreview = false; removeCallbacks(releaseNeedlePreview);
            if (needleListener != null) needleListener.onNeedlePause();
        }
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        updateAnimation(); invalidate();
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activePointer = event.getPointerId(0); downX = event.getX(); downY = event.getY(); touchCancelled = false;
                needleCandidate = needleSeekingEnabled && needleListener != null && hitsArm(downX, downY); needleDragging = false;
                dragOriginAngle = armAngle; dragAngle = armAngle; downPointerAngle = pointerAngle(downX, downY);
                setPressed(!needleCandidate);
                if (needleCandidate && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                int index = event.findPointerIndex(activePointer);
                if (index < 0) return true;
                float x = event.getX(index), y = event.getY(index), dx = x - downX, dy = y - downY;
                boolean moved = dx * dx + dy * dy > touchSlop * touchSlop;
                if (needleCandidate && !needleDragging && moved) startNeedleDrag();
                if (needleDragging) moveNeedle(x, y);
                else if (moved) { touchCancelled = true; setPressed(false); }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(event.getActionIndex()) == activePointer) {
                    if (needleDragging) finishNeedleDrag(false);
                    activePointer = -1; touchCancelled = true; needleCandidate = false; setPressed(false);
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;
            case MotionEvent.ACTION_UP:
                setPressed(false);
                // ACTION_UP carries the final sample, which may differ from the last MOVE.
                // In particular, releasing at the rest must never commit a stale on-disc pose.
                int releaseIndex = event.findPointerIndex(activePointer);
                if (releaseIndex >= 0 && !touchCancelled) {
                    float releaseX = event.getX(releaseIndex), releaseY = event.getY(releaseIndex);
                    float releaseDx = releaseX - downX, releaseDy = releaseY - downY;
                    boolean movedOnRelease = releaseDx * releaseDx + releaseDy * releaseDy > touchSlop * touchSlop;
                    if (needleCandidate && !needleDragging && movedOnRelease) startNeedleDrag();
                    if (needleDragging) moveNeedle(releaseX, releaseY);
                    else if (movedOnRelease) touchCancelled = true;
                }
                if (needleDragging) finishNeedleDrag(true);
                else if (needleCandidate && !touchCancelled && needleListener != null) {
                    if (playing) needleListener.onNeedlePause(); else needleListener.onNeedlePlay();
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                } else if (!touchCancelled) performClick();
                activePointer = -1; needleCandidate = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (needleDragging) finishNeedleDrag(false);
                touchCancelled = true; needleCandidate = false; activePointer = -1; setPressed(false);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default: return super.onTouchEvent(event);
        }
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (needleListener == null || !needleSeekingEnabled) return;
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(playing ? ACCESSIBILITY_PAUSE : ACCESSIBILITY_PLAY,
                playing ? "抬针暂停" : "落针播放"));
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0, 1, fraction));
    }
    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (needleListener != null && needleSeekingEnabled) {
            if (action == ACCESSIBILITY_PLAY) { needleListener.onNeedlePlay(); return true; }
            if (action == ACCESSIBILITY_PAUSE) { needleListener.onNeedlePause(); return true; }
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                needleListener.onNeedleSeek(clamp(fraction + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? .05f : -.05f), 0, 1)); return true;
            }
            if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId() && arguments != null
                    && arguments.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)) {
                float value = arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE);
                if (!Float.isNaN(value)) needleListener.onNeedleSeek(clamp(value, 0, 1));
                return true;
            }
        }
        return super.performAccessibilityAction(action, arguments);
    }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private static float clamp(float value, float low, float high) { return Math.max(low, Math.min(high, value)); }
    private static float smooth(float value) { float t=clamp(value,0,1);return t*t*(3-2*t); }
}
