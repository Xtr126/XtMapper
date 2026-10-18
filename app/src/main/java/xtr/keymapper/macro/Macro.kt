package xtr.keymapper.macro

import android.os.Parcel
import android.os.Parcelable
import xtr.keymapper.server.IInputInterface
import xtr.keymapper.server.InputService

class Macro : Parcelable {

    private val events: Array<Event?>?
    @JvmField
    var triggerKey: String? = null

    /**
     * @param data format: x1 y1 0; x2 y2 elapsedTimeSinceLastEventMillis;
     */
    constructor(data: String) {
        val eventsData = data.split(";").dropLastWhile { it.isEmpty() }.toTypedArray()
        events = arrayOfNulls(eventsData.size)

        for (i in eventsData.indices) {
            val eventData =
                eventsData[i].split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            events[i] = Event().also {
                it.x = eventData[0].toFloat()
                it.y = eventData[1].toFloat()
                it.elapsedTimeSinceLastEventMillis = eventData[2].toInt()
            }
        }
    }

    // For editor
    constructor() {
        events = null
    }

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    protected constructor(parcel: Parcel) {
        events = parcel.createTypedArray(Event.CREATOR) as Array<Event?>?
        triggerKey = parcel.readString()
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeTypedArray(events, flags)
        dest.writeString(triggerKey)
    }

    override fun describeContents(): Int {
        return 0
    }

    /**
     * Blocking operation
     * Run in thread
     */
    fun runMacro(mInput: IInputInterface, pointerId: Int) {
        val events = this.events!!
        // Initial input event
        mInput.injectEvent(events[0]!!.x, events[0]!!.y, InputService.DOWN, pointerId)

        // Inject one by one with delay
        for (i in 1 until events.size - 1) {
            try {
                Thread.sleep(events[i]!!.elapsedTimeSinceLastEventMillis.toLong())
                mInput.injectEvent(events[i]!!.x, events[i]!!.y, InputService.MOVE, pointerId)
            } catch (ignored: InterruptedException) {
            }
        }
        // End input event sequence
        val last = events[events.size - 1]!!
        mInput.injectEvent(last.x, last.y, InputService.UP, pointerId)
    }

    private class Event : Parcelable {
        var x = 0f
        var y = 0f
        var elapsedTimeSinceLastEventMillis = 0

        constructor()

        protected constructor(parcel: Parcel) {
            x = parcel.readFloat()
            y = parcel.readFloat()
            elapsedTimeSinceLastEventMillis = parcel.readInt()
        }

        override fun describeContents(): Int {
            return 0
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeFloat(x)
            dest.writeFloat(y)
            dest.writeInt(elapsedTimeSinceLastEventMillis)
        }

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<Event> = object : Parcelable.Creator<Event> {
                override fun createFromParcel(parcel: Parcel): Event {
                    return Event(parcel)
                }

                override fun newArray(size: Int): Array<Event?> {
                    return arrayOfNulls(size)
                }
            }
        }
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<Macro> = object : Parcelable.Creator<Macro> {
            override fun createFromParcel(parcel: Parcel): Macro {
                return Macro(parcel)
            }

            override fun newArray(size: Int): Array<Macro?> {
                return arrayOfNulls(size)
            }
        }
    }
}
