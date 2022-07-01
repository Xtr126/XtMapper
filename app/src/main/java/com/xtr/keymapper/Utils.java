package com.xtr.keymapper;

import java.math.BigInteger;

public class Utils {
    public static String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    public static Number hexToDec(String hex)  {
        if (hex == null) {
            throw new NullPointerException("hexToDec: hex String is null.");
        }

        // You may want to do something different with the empty string.
        if (hex.equals("")) { return Byte.valueOf("0"); }

        // If you want to pad "FFF" to "0FFF" do it here.

        hex = hex.toUpperCase();

        // Check if high bit is set.
        boolean isNegative =
                hex.startsWith("8") || hex.startsWith("9") ||
                        hex.startsWith("A") || hex.startsWith("B") ||
                        hex.startsWith("C") || hex.startsWith("D") ||
                        hex.startsWith("E") || hex.startsWith("F");

        BigInteger temp;

        temp = new BigInteger(hex, 16);
        if (isNegative) {
            // Negative number
            BigInteger subtrahend = BigInteger.ONE.shiftLeft(hex.length() * 4);
            temp = temp.subtract(subtrahend);
        }  // Positive number


        // Cut BigInteger down to size.
        if (hex.length() <= 2) { return temp.byteValue(); }
        if (hex.length() <= 4) { return temp.shortValue(); }
        if (hex.length() <= 8) { return temp.intValue(); }
        if (hex.length() <= 16) { return temp.longValue(); }
        return temp;
    }
    public static int obtainIndex(String s) {
        return alphabet.indexOf(s.substring(4));
    }

}
