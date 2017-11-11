 package org.janelia.alignment.filter;

 import ij.process.ColorProcessor;
 import ij.process.FloatProcessor;
 import ij.process.ImageProcessor;


public class SubstituteValue implements Filter {
  final static public void processFloat(
    final FloatProcessor ip, final float value, final double subValue) {
      final int n = ip.getWidth() * ip.getHeight();
      for (int i = 0; i < n; ++i) {
        final float v = ip.getf(i);
        if (v == value)
          ip.setf(i, (float) subValue);
      }
  }

  final static public void processGray(
    final ImageProcessor ip, final int value, final int subValue) {
      final int n = ip.getWidth() * ip.getHeight();
      for (int i = 0; i < n; ++i) {
        final int v = ip.get(i);
        if (v == value)
          ip.set(i, subValue);
      }
    }

  // FIXME ColorProcessor support
  final static public void processColor(final ColorProcessor ip,
                                        final int value, final int subValue) {
    final int n = ip.getWidth() * ip.getHeight();
    for (int i = 0; i < n; ++i) {
      // TODO what is a reasonable setup for this?
      final int v = ip.get(i) & 0x00ffffff;
      if (v == value)
        ip.set(i, subValue);
    }
  }

}
