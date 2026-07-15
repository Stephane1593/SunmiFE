package woyou.aidlservice.jiuiv5;

import woyou.aidlservice.jiuiv5.ICallback;
import android.graphics.Bitmap;

interface IWoyouService {
    void printerInit(in ICallback callback);
    void printerSelfChecking(in ICallback callback);
    String getPrinterSerialNo();
    String getPrinterVersion();
    String getPrinterModal();
    int updatePrinterState();
    void sendRAWData(in byte[] data, in ICallback callback);
    void setAlignment(int alignment, in ICallback callback);
    void setFontName(String typeface, in ICallback callback);
    void setFontSize(float fontsize, in ICallback callback);
    void printTextWithFont(String text, String typeface, float fontsize, in ICallback callback);
    void printOriginalText(String text, in ICallback callback);
    void printColumnsText(in String[] colsTextArr, in int[] colsWidthArr, in int[] colsAlign, in ICallback callback);
    void printBitmap(in Bitmap bitmap, in ICallback callback);
    void printBarCode(String data, int symbology, int height, int width, int textposition, in ICallback callback);
    void printQRCode(String data, int modulesize, int errorlevel, in ICallback callback);
    void printText(String text, in ICallback callback);
    void lineWrap(int n, in ICallback callback);
    void cutPaper(in ICallback callback);
    void getPrintedLength(in ICallback callback);
    void openDrawer(in ICallback callback);
    void enterPrinterBuffer(boolean clean);
    void exitPrinterBuffer(boolean commit);
    void commitPrinterBuffer();
    void printBarCodeInBuffer(String data, int symbology, int height, int width, int textposition);
    void printQRCodeInBuffer(String data, int modulesize, int errorlevel);
    void printTextInBuffer(String text);
    void printOriginalTextInBuffer(String text);
    void printTextWithFontInBuffer(String text, String typeface, float fontsize);
    void printColumnsTextInBuffer(in String[] colsTextArr, in int[] colsWidthArr, in int[] colsAlign);
    void printBitmapInBuffer(in Bitmap bitmap);
    void lineWrapInBuffer(int n);
    void cutPaperInBuffer();
    void setAlignmentInBuffer(int alignment);
    void setFontNameInBuffer(String typeface);
    void setFontSizeInBuffer(float fontsize);
    void sendRAWDataInBuffer(in byte[] data);
    void commitPrinterBufferWithCallback(in ICallback callback);
    void exitPrinterBufferWithCallback(boolean commit, in ICallback callback);
}