package xtr.keymapper.swipekey;

import xtr.keymapper.profiles.KeymapProfiles;

public class SwipeKey {
    public final KeymapProfiles.Key key1 = new KeymapProfiles.Key();
    public final KeymapProfiles.Key key2 = new KeymapProfiles.Key();

    public static final String type = "SWIPE_KEY";

    public SwipeKey (String[] data){
        key1.code = data[1];
        key1.x = Float.parseFloat(data[2]);
        key1.y = Float.parseFloat(data[3]);

        key2.code = data[4];
        key2.x = Float.parseFloat(data[5]);
        key2.y = Float.parseFloat(data[6]);
    }

    public SwipeKey(SwipeKeyView swipeKey){
        key1.code = swipeKey.button1.getText();
        key1.x = swipeKey.button1.getX();
        key1.y = swipeKey.button1.getY();

        key2.code = swipeKey.button2.getText();
        key2.x = swipeKey.button2.getX();
        key2.y = swipeKey.button2.getY();
    }

    public String getData(){
        return type + " " +
                key1.code + " " +
                key1.x + " " +
                key1.y + " " +
                key2.code + " " +
                key2.x + " " +
                key2.y;
    }
}
