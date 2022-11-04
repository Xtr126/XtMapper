package com.xtr.keymapper.floatingkeys;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.shape.RelativeCornerSize;
import com.google.android.material.shape.RoundedCornerTreatment;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.xtr.keymapper.R;

import java.util.Random;

public class MovableFloatingActionButton extends FloatingActionButton  {

    public String key;

    public MovableFloatingActionButton(Context context) {
        super(context);
        init();
    }

    public MovableFloatingActionButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MovableFloatingActionButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setShapeAppearanceModel(new ShapeAppearanceModel()
                .toBuilder()
                .setAllCorners(new RoundedCornerTreatment()).setAllCornerSizes(new RelativeCornerSize(0.5f))
                .build());
    }

    public void setButtonActive(){
        Random rnd = new Random();
        int color = Color.argb(255, rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256));
        setBackgroundTintList(ColorStateList.valueOf(color));
        setImageTintList(ColorStateList.valueOf(getContext().getColor(R.color.colorAccent)));
    }

    public void setButtonInactive(){
        setBackgroundTintList(ColorStateList.valueOf(getContext().getColor(R.color.grey)));
        setImageTintList(ColorStateList.valueOf(getContext().getColor(R.color.white2)));
    }


    public void setText(String text) {
        this.key = text;
        setButtonActive();
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(30);
        paint.setFakeBoldText(true);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.LEFT);
        float baseline = -paint.ascent(); // ascent() is negative
        int width = (int) (paint.measureText(text) + 0.0f); // round
        int height = (int) (baseline + paint.descent() + 0.0f);
        Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

        Canvas canvas = new Canvas(image);
        canvas.drawText(text, 0, baseline, paint);
        setImageBitmap(image);
        setScaleType(ScaleType.CENTER);
        //setMaxImageSize(30);
    }
}