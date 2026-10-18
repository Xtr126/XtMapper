package xtr.keymapper.floatingkeys

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.shape.RelativeCornerSize
import com.google.android.material.shape.RoundedCornerTreatment
import com.google.android.material.shape.ShapeAppearanceModel
import xtr.keymapper.R
import java.util.Random

class FloatingActionKey : FloatingActionButton {

    @JvmField
    var key: String = "X"

    private var colorInactive: ColorStateList? = null
    private var textColor: ColorStateList? = null
    private var textColorInactive: ColorStateList? = null

    constructor(context: Context) : super(context) {
        colorInactive = AppCompatResources.getColorStateList(context, R.color.grey)
        textColor = AppCompatResources.getColorStateList(context, R.color.black)
        textColorInactive = AppCompatResources.getColorStateList(context, R.color.white)
        init()
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr) {
        init()
    }

    private fun init() {
        shapeAppearanceModel = ShapeAppearanceModel()
            .toBuilder()
            .setAllCorners(RoundedCornerTreatment()).setAllCornerSizes(RelativeCornerSize(0.5f))
            .build()
    }

    fun setButtonActive() {
        val rnd = Random()
        val color = Color.argb(255, rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
        backgroundTintList = ColorStateList.valueOf(color)
        imageTintList = textColor
    }

    fun setButtonInactive() {
        backgroundTintList = colorInactive
        imageTintList = textColorInactive
    }

    fun setText(text: String, size: Int) {
        this.key = text
        setButtonActive()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.textSize = size.toFloat()
        paint.isFakeBoldText = true
        paint.color = Color.BLACK
        paint.textAlign = Paint.Align.LEFT
        val baseline = -paint.ascent() // ascent() is negative
        val width = paint.measureText(text).toInt() // round
        val height = (baseline + paint.descent()).toInt()
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val canvas = Canvas(image)
        canvas.drawText(text, 0f, baseline, paint)
        setImageBitmap(image)
        scaleType = ScaleType.CENTER
    }

    override fun setImageResource(resId: Int) {
        super.setImageResource(resId)
        scaleType = ScaleType.CENTER
    }
}
