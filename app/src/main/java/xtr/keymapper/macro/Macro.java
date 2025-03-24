package xtr.keymapper.macro;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import xtr.keymapper.server.IInputInterface;
import xtr.keymapper.server.InputService;

public class Macro implements Parcelable {

    private final Event[] events;
    public String triggerKey;

    /**
     * @param data format: x1 y1 0; x2 y2 elapsedTimeSinceLastEventMillis;
     */
    public Macro(String data) {
        String[] eventsData = data.split(";");
        this.events = new Event[eventsData.length];

        for (int i = 0; i < eventsData.length; i++) {
            String[] eventData = eventsData[i].split("\\s+");
            this.events[i] = new Event();
            this.events[i].x = Float.parseFloat(eventData[0]);
            this.events[i].y = Float.parseFloat(eventData[1]);
            this.events[i].elapsedTimeSinceLastEventMillis = Integer.parseInt(eventData[2]);
        }
    }

    // For editor
    public Macro() {
        events = null;
    }

    protected Macro(Parcel in) {
        events = in.createTypedArray(Event.CREATOR);
        triggerKey = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeTypedArray(events, flags);
        dest.writeString(triggerKey);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<Macro> CREATOR = new Creator<>() {
        @Override
        public Macro createFromParcel(Parcel in) {
            return new Macro(in);
        }

        @Override
        public Macro[] newArray(int size) {
            return new Macro[size];
        }
    };

    /**
     * Blocking operation
     * Run in thread
     */
    public void runMacro(IInputInterface mInput, int pointerId) {
        // Initial input event
        mInput.injectEvent(events[0].x, events[0].y, InputService.DOWN, pointerId);

        // Inject one by one with delay
        for (int i = 1; i < events.length - 1; i++) {
            try {
                Thread.sleep(events[i].elapsedTimeSinceLastEventMillis);
                mInput.injectEvent(events[i].x, events[i].y, InputService.MOVE, pointerId);
            } catch (InterruptedException ignored) {
            }
        }
        // End input event sequence
        mInput.injectEvent(events[events.length - 1].x, events[events.length - 1].y, InputService.UP, pointerId);

    }

    private static class Event implements Parcelable {
        float x, y;
        int elapsedTimeSinceLastEventMillis;

        protected Event(Parcel in) {
            x = in.readFloat();
            y = in.readFloat();
            elapsedTimeSinceLastEventMillis = in.readInt();
        }

        public static final Creator<Event> CREATOR = new Creator<>() {
            @Override
            public Event createFromParcel(Parcel in) {
                return new Event(in);
            }

            @Override
            public Event[] newArray(int size) {
                return new Event[size];
            }
        };

        public Event() {
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeFloat(x);
            dest.writeFloat(y);
            dest.writeInt(elapsedTimeSinceLastEventMillis);
        }
    }
}
