import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

public class StyleImportSmoke {
    public static void main(String[] args) throws Exception {
        File input = File.createTempFile("style-import-smoke-", ".docx");
        try (XWPFDocument document = new XWPFDocument(); FileOutputStream output = new FileOutputStream(input)) {
            XWPFParagraph heading = document.createParagraph();
            heading.setAlignment(org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER);
            XWPFRun headingRun = heading.createRun();
            headingRun.setBold(true);
            headingRun.setItalic(true);
            headingRun.setFontSize(14);
            headingRun.setText("FINDINGS");
            XWPFParagraph body = document.createParagraph();
            body.setIndentationLeft(720);
            XWPFRun bodyRun = body.createRun();
            bodyRun.setItalic(true);
            bodyRun.setText("Styled report text");
            document.write(output);
        }

        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        Object app = unsafe.allocateInstance(com.radiology.App.class);
        Method converter = com.radiology.App.class.getDeclaredMethod("convertDocxToHtml", File.class);
        converter.setAccessible(true);
        String html = (String) converter.invoke(app, input);
        if (!html.contains("FINDINGS") || !html.contains("font-weight:bold") || !html.contains("text-align:center")) {
            throw new AssertionError("Styled DOCX import did not produce expected HTML: " + html);
        }
        System.out.println("DOCX style import smoke test passed");
        input.delete();
    }
}
