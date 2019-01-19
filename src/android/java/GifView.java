package com.allpet.nosedetection;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Movie;
import android.util.AttributeSet;
import android.view.View;


public class GifView extends View {

    private long movieStart;
    private Movie movie;

    public GifView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);

    }

    public void setGifResource(int resId)
    {
        movie = Movie.decodeStream(getResources().openRawResource(resId));
    }

    @Override
    protected void onDraw(Canvas canvas) {

        long curTime = android.os.SystemClock.uptimeMillis();

        // First play
        if (movieStart == 0) {
            movieStart = curTime;
        }
        if (movie != null) {
            int duraction = movie.duration();
            int relTime = (int) ((curTime - movieStart) % duraction);
            movie.setTime(relTime);
            movie.draw(canvas, 0, 0);
            // Force redraw
            invalidate();
        }

        super.onDraw(canvas);
    }
}