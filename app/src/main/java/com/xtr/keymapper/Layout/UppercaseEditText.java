package com.xtr.keymapper.Layout;

import android.content.Context;

import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;



public class UppercaseEditText extends androidx.appcompat.widget.AppCompatEditText implements TextWatcher {

    public UppercaseEditText(Context context) {
        super(context);
        init();
    }

    public UppercaseEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public UppercaseEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        addTextChangedListener(this);
    }

    @Override
    public void onTextChanged(CharSequence arg0, int arg1, int arg2, int arg3) {

    }
    @Override
    public void beforeTextChanged(CharSequence arg0, int arg1, int arg2,
                                  int arg3) {
    }
    public void afterTextChanged(Editable et) {
        String s=et.toString();
        if(!s.equals(s.toUpperCase())) {
            s=s.toUpperCase();
            setText(s);
        }
    }

}