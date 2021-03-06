package com.marsvard.brokenmaps;

import org.oscim.core.Tile;
import org.oscim.event.Event;
import org.oscim.event.Gesture;
import org.oscim.event.GestureListener;
import org.oscim.event.MotionEvent;
import org.oscim.layers.Layer;
import org.oscim.map.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.oscim.backend.CanvasAdapter.dpi;
import static org.oscim.utils.FastMath.withinSquaredDist;

/**
 * COPIED by Mars on 23/08/14.
 */

/**
 * Changes Viewport by handling move, fling, scale, rotation and tilt gestures.
 * <p/>
 * TODO rewrite using gesture primitives to build more complex gestures:
 * maybe something similar to this https://github.com/ucbvislab/Proton
 */
public class CustomEventLayer extends Layer implements Map.InputListener, GestureListener {

    static final Logger log = LoggerFactory.getLogger(CustomEventLayer.class);
    private final MapEventListener mMapeventListener;

    public enum MapEvents {MOVE, TILT, ROTATE, ZOOM, TAP}

    private boolean mEnableRotate = true;
    private boolean mEnableTilt = true;
    private boolean mEnableMove = true;
    private boolean mEnableScale = true;
    private boolean mFixOnCenter = false;

    /* possible state transitions */
    private boolean mCanScale;
    private boolean mCanRotate;
    private boolean mCanTilt;

    /* current gesture state */
    private boolean mDoRotate;
    private boolean mDoScale;
    private boolean mDoTilt;

    private boolean mDown;
    private boolean mDoubleTap;
    private boolean mDragZoom;

    private float mPrevX1;
    private float mPrevY1;
    private float mPrevX2;
    private float mPrevY2;

    private double mAngle;
    private double mPrevPinchWidth;
    private long mStartMove;

    /**
     * 2mm as minimal distance to start move: dpi / 25.4
     */
    protected static final float MIN_SLOP = 25.4f / 2;

    protected static final float PINCH_ZOOM_THRESHOLD = MIN_SLOP / 2;
    protected static final float PINCH_TILT_THRESHOLD = MIN_SLOP / 2;
    protected static final float PINCH_TILT_SLOPE = 0.75f;
    protected static final float PINCH_ROTATE_THRESHOLD = 0.2f;
    protected static final float PINCH_ROTATE_THRESHOLD2 = 0.5f;

    /**
     * 100 ms since start of move to reduce fling scroll
     */
    protected static final float FLING_MIN_THREHSHOLD = 100;

    private final VelocityTracker mTracker;

    public CustomEventLayer(Map map, MapEventListener mapEventListener) {
        super(map);
        mTracker = new VelocityTracker();
        mMapeventListener = mapEventListener;
    }

    @Override
    public void onInputEvent(Event e, MotionEvent motionEvent) {
        onTouchEvent(motionEvent);
    }

    public void enableRotation(boolean enable) {
        mEnableRotate = enable;
    }

    public boolean rotationEnabled() {
        return mEnableRotate;
    }

    public void enableTilt(boolean enable) {
        mEnableTilt = enable;
    }

    public void enableMove(boolean enable) {
        mEnableMove = enable;
    }

    public void enableZoom(boolean enable) {
        mEnableScale = enable;
    }

    /**
     * When enabled zoom- and rotation-gestures will not move the viewport.
     */
    public void setFixOnCenter(boolean enable) {
        mFixOnCenter = enable;
    }

    public boolean onTouchEvent(MotionEvent e) {

        int action = getAction(e);

        if (action == MotionEvent.ACTION_DOWN) {
            mMap.animator().cancel();

            mStartMove = -1;
            mDoubleTap = false;
            mDragZoom = false;

            mPrevX1 = e.getX(0);
            mPrevY1 = e.getY(0);

            mDown = true;
            return false;
        }
        if (!(mDown || mDoubleTap)) {
            /* no down event received */
            return false;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            onActionMove(e);
            return false;
        }
        if (action == MotionEvent.ACTION_UP) {
            mDown = false;
            if (mDoubleTap && !mDragZoom) {
                float pivotX = 0, pivotY = 0;
                if (!mFixOnCenter) {
                    pivotX = mPrevX1 - mMap.getWidth() / 2;
                    pivotY = mPrevY1 - mMap.getHeight() / 2;
                }

				/* handle double tap zoom */
                mMap.animator().animateZoom(300, 2, pivotX, pivotY);

            } else if (mStartMove > 0) {
				/* handle fling gesture */
                mTracker.update(e.getX(), e.getY(), e.getTime());
                float vx = mTracker.getVelocityX();
                float vy = mTracker.getVelocityY();

				/* reduce velocity for short moves */
                float t = e.getTime() - mStartMove;
                if (t < FLING_MIN_THREHSHOLD) {
                    t = t / FLING_MIN_THREHSHOLD;
                    vy *= t * t;
                    vx *= t * t;
                }
                doFling(vx, vy);
            }else{
                mMapeventListener.onMapTouchEvent(MapEvents.TAP, e);
            }
            return false;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            return false;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            mStartMove = -1;
            updateMulti(e);
            return false;
        }
        if (action == MotionEvent.ACTION_POINTER_UP) {
            updateMulti(e);
            return false;
        }

        return false;
    }

    private static int getAction(MotionEvent e) {
        return e.getAction() & MotionEvent.ACTION_MASK;
    }

    private void onActionMove(MotionEvent e) {
        float x1 = e.getX(0);
        float y1 = e.getY(0);

        float mx = x1 - mPrevX1;
        float my = y1 - mPrevY1;

        float width = mMap.getWidth();
        float height = mMap.getHeight();

        if (e.getPointerCount() < 2) {
            mPrevX1 = x1;
            mPrevY1 = y1;

			/* double-tap drag zoom */
            if (mDoubleTap) {
				/* just ignore first move event to set mPrevX/Y */
                if (!mDown) {
                    mDown = true;
                    return;
                }
                if (!mDragZoom && !isMinimalMove(mx, my)) {
                    mPrevX1 -= mx;
                    mPrevY1 -= my;
                    return;
                }

                // TODO limit scale properly
                mDragZoom = true;
                mStartMove = -1;

                mMapeventListener.onMapTouchEvent(MapEvents.ZOOM, e);
                return;
            }

			/* simple move */
            if (!mEnableMove)
                return;

            if (mStartMove < 0) {
                if (!isMinimalMove(mx, my)) {
                    mPrevX1 -= mx;
                    mPrevY1 -= my;
                    return;
                }

                mStartMove = e.getTime();
                mTracker.start(x1, y1, mStartMove);
                mMapeventListener.onMapTouchEvent(MapEvents.MOVE, e);
                return;
            }
            mTracker.update(x1, y1, e.getTime());
            return;
        }
        mStartMove = -1;

        float x2 = e.getX(1);
        float y2 = e.getY(1);
        float dx = (x1 - x2);
        float dy = (y1 - y2);

        double rotateBy = 0;
        float scaleBy = 1;
        float tiltBy = 0;

        mx = ((x1 + x2) - (mPrevX1 + mPrevX2)) / 2;
        my = ((y1 + y2) - (mPrevY1 + mPrevY2)) / 2;

        if (mCanTilt) {
            float slope = (dx == 0) ? 0 : dy / dx;

            if (Math.abs(slope) < PINCH_TILT_SLOPE) {

                if (mDoTilt) {
                    tiltBy = my / 5;
                } else if (Math.abs(my) > (dpi / PINCH_TILT_THRESHOLD)) {
					/* enter exclusive tilt mode */
                    mCanScale = false;
                    mCanRotate = false;
                    mDoTilt = true;
                    mMapeventListener.onMapTouchEvent(MapEvents.TILT, e);
                }
            }
        }

        double pinchWidth = Math.sqrt(dx * dx + dy * dy);
        double deltaPinch = pinchWidth - mPrevPinchWidth;

        if (mCanRotate) {
            double rad = Math.atan2(dy, dx);
            double r = rad - mAngle;

            if (mDoRotate) {
                double da = rad - mAngle;

                if (Math.abs(da) > 0.0001) {
                    rotateBy = da;
                    mAngle = rad;

                    deltaPinch = 0;
                }
                mMapeventListener.onMapTouchEvent(MapEvents.ROTATE, e);
            } else {
                r = Math.abs(r);
                if (r > PINCH_ROTATE_THRESHOLD) {
					/* start rotate, disable tilt */
                    mDoRotate = true;
                    mCanTilt = false;

                    mAngle = rad;
                } else if (!mDoScale) {
					/* reduce pinch trigger by the amount of rotation */
                    deltaPinch *= 1 - (r / PINCH_ROTATE_THRESHOLD);
                } else {
                    mPrevPinchWidth = pinchWidth;
                }
            }
        } else if (mDoScale && mEnableRotate) {
			/* re-enable rotation when higher threshold is reached */
            double rad = Math.atan2(dy, dx);
            double r = rad - mAngle;

            if (r > PINCH_ROTATE_THRESHOLD2) {
				/* start rotate again */
                mDoRotate = true;
                mCanRotate = true;
                mAngle = rad;
            }
        }

        if (mCanScale || mDoRotate) {
            if (!(mDoScale || mDoRotate)) {
				/* enter exclusive scale mode */
                if (Math.abs(deltaPinch) > (dpi / PINCH_ZOOM_THRESHOLD)) {

                    if (!mDoRotate) {
                        mPrevPinchWidth = pinchWidth;
                        mCanRotate = false;
                    }

                    mCanTilt = false;
                    mDoScale = true;
                    mMapeventListener.onMapTouchEvent(MapEvents.ZOOM, e);

                }
            }
            if (mDoScale || mDoRotate) {
                scaleBy = (float) (pinchWidth / mPrevPinchWidth);
                mPrevPinchWidth = pinchWidth;
            }
        }

        if (!(mDoRotate || mDoScale || mDoTilt))
            return;

        float pivotX = 0, pivotY = 0;

        if (!mFixOnCenter) {
            pivotX = (x2 + x1) / 2 - width / 2;
            pivotY = (y2 + y1) / 2 - height / 2;
        }

        mPrevX1 = x1;
        mPrevY1 = y1;
        mPrevX2 = x2;
        mPrevY2 = y2;
    }

    private void updateMulti(MotionEvent e) {
        int cnt = e.getPointerCount();

        mPrevX1 = e.getX(0);
        mPrevY1 = e.getY(0);

        if (cnt == 2) {
            mDoScale = false;
            mDoRotate = false;
            mDoTilt = false;
            mCanScale = mEnableScale;
            mCanRotate = mEnableRotate;
            mCanTilt = mEnableTilt;

            mPrevX2 = e.getX(1);
            mPrevY2 = e.getY(1);
            double dx = mPrevX1 - mPrevX2;
            double dy = mPrevY1 - mPrevY2;

            mAngle = Math.atan2(dy, dx);
            mPrevPinchWidth = Math.sqrt(dx * dx + dy * dy);
        }
    }

    private boolean isMinimalMove(float mx, float my) {
        float minSlop = (dpi / MIN_SLOP);
        return !withinSquaredDist(mx, my, minSlop * minSlop);
    }

    private boolean doFling(float velocityX, float velocityY) {

        int w = Tile.SIZE * 5;
        int h = Tile.SIZE * 5;

        mMap.animator().animateFling(velocityX * 2, velocityY * 2,
                -w, w, -h, h);
        return false;
    }

    @Override
    public boolean onGesture(Gesture g, MotionEvent e) {
        if (g == Gesture.DOUBLE_TAP) {
            mDoubleTap = true;
            return false;
        }
        return false;
    }

    static class VelocityTracker {
        /* sample window, 200ms */
        private static final int MAX_MS = 200;
        private static final int SAMPLES = 32;

        private float mLastX, mLastY;
        private long mLastTime;
        private int mNumSamples;
        private int mIndex;

        private float[] mMeanX = new float[SAMPLES];
        private float[] mMeanY = new float[SAMPLES];
        private int[] mMeanTime = new int[SAMPLES];

        public void start(float x, float y, long time) {
            mLastX = x;
            mLastY = y;
            mNumSamples = 0;
            mIndex = SAMPLES;
            mLastTime = time;
        }

        public void update(float x, float y, long time) {
            if (time == mLastTime)
                return;

            if (--mIndex < 0)
                mIndex = SAMPLES - 1;

            mMeanX[mIndex] = x - mLastX;
            mMeanY[mIndex] = y - mLastY;
            mMeanTime[mIndex] = (int) (time - mLastTime);

            mLastTime = time;
            mLastX = x;
            mLastY = y;

            mNumSamples++;
        }

        private float getVelocity(float[] move) {
            mNumSamples = Math.min(SAMPLES, mNumSamples);

            double duration = 0;
            double amount = 0;

            for (int c = 0; c < mNumSamples; c++) {
                int index = (mIndex + c) % SAMPLES;

                float d = mMeanTime[index];
                if (c > 0 && duration + d > MAX_MS)
                    break;

                duration += d;
                amount += move[index] * (d / duration);
            }

            if (duration == 0)
                return 0;

            return (float) ((amount * 1000) / duration);
        }

        public float getVelocityY() {
            return getVelocity(mMeanY);
        }

        public float getVelocityX() {
            return getVelocity(mMeanX);
        }
    }

    public interface MapEventListener {
        public void onMapTouchEvent(MapEvents action, MotionEvent e);
    }
}
