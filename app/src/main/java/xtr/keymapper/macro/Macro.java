package xtr.keymapper.macro;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import xtr.keymapper.server.IInputInterface;
import xtr.keymapper.server.InputService;

public class Macro implements Parcelable {

    protected Macro(Parcel in) {
        events = in.createTypedArray(Event.CREATOR);
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

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedArray(events, flags);
    }

    private final Event[] events;

    /**
     * @param data format: x1 y1 0; x2 y2 elapsedTimeSinceLastEventMillis;
     */
    public Macro(String data) {
        String[] events = data.split(";");
        this.events = new Event[data.length()];

        for (int i = 0; i < events.length; i++) {
            String[] eventData = events[i].split("\\s+");
            this.events[i].x = Float.parseFloat(eventData[0]);
            this.events[i].y = Float.parseFloat(eventData[1]);
            this.events[i].elapsedTimeSinceLastEventMillis = Integer.parseInt(eventData[2]);
        }
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
