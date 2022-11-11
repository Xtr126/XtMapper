package com.xtr.keymapper.floatingkeys;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;

import androidx.appcompat.content.res.AppCompatResources;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.shape.RelativeCornerSize;
import com.google.android.material.shape.RoundedCornerTreatment;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.xtr.keymapper.R;

import java.util.Random;

public class FloatingActionKey extends FloatingActionButton  {

    public String key;
    private ColorStateList colorInactive;
    private ColorStateList textColor;
    private ColorStateList textColorInactive;

    public FloatingActionKey(Context context) {
        super(context);
        colorInactive = AppCompatResources.getColorStateList(context, R.color.grey);
        textColor = AppCompatResources.getColorStateList(context, R.color.black);
        textColorInactive = AppCompatResources.getColorStateList(context, R.color.white);
        init();
    }

    public FloatingActionKey(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public FloatingActionKey(Context context, AttributeSet attrs, int defStyleAttr) {
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
        setImageTintList(textColor);
    }

    public void setButtonInactive(){
        setBackgroundTintList(colorInactive);
        setImageTintList(textColorInactive);
    }

    public void setText(String text) {
        this.key = text;
        setButtonActive();
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(30);
        paint.setFakeBoldText(true);
        paint.setColor(Color.BLACK);
        paint.setTextAlign(Paint.Align.LEFT);
        float baseline = -paint.ascent(); // ascent() is negative
        int width = (int) (paint.measureText(text) + 0.0f); // round
        int height = (int) (baseline + paint.descent() + 0.0f);
        Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

        Canvas canvas = new Canvas(image);
        canvas.drawText(text, 0, baseline, paint);
        setImageBitmap(image);
        setScaleType(ScaleType.CENTER);
    }
}