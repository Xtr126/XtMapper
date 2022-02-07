package com.xtr.keymapper.Layout;
import android.content.Context;
import android.graphics.Color;
import android.text.InputFilter;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.ImageView;

import com.xtr.keymapper.Layout.MovableFrameLayout;
import com.xtr.keymapper.Layout.UppercaseEditText;
import com.xtr.keymapper.R;


public class XtKeyLayout extends MovableFrameLayout {
    private ImageView key;
    private  UppercaseEditText KeyText;
    public XtKeyLayout(Context context) {
        super(context);
        init();
    }

    public XtKeyLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public XtKeyLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        Context context = getContext();
        key = new ImageView(context);
        key.setMaxHeight(50);
        key.setMaxWidth(50);
        key.setImageResource(R.drawable.key);

        KeyText = new UppercaseEditText(context);
        KeyText.setWidth(28);
        KeyText.setHeight(55);
        KeyText.setTextColor(Color.WHITE);
        KeyText.setCursorVisible(false);
        KeyText.setTextSize(30);
        KeyText.setFilters(new InputFilter[] { new InputFilter.LengthFilter(1) });

        LayoutParams l1 = new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        l1.height = 50; l1.width = 50;
        addView(key, l1);

        LayoutParams l2 = new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        l2.height = 55; l2.width = 28;
        addView(KeyText, l2);
    }
    public String getData(){
        return "KEY_" + KeyText.getText() + " " + getX() + " " + getY() + "\n";
    }
    public void setKeyText(String text){
        KeyText.setText(text);
    }
}