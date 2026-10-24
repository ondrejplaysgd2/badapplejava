import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.InflaterInputStream;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/** Plays the lossless RGB pixel-delta stream while decoding audio from the supplied MP4. */
public final class BadApplePlayer {
    private static final int WIDTH = 480, HEIGHT = 360, FPS = 30, AUDIO_RATE = 44100;
    private static final int PIXELS = WIDTH * HEIGHT;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java BadApplePlayer.java <Bad-Apple-video.mp4> [ffmpeg-path]");
            System.exit(2);
        }
        Path video = Path.of(args[0]).toAbsolutePath();
        String ffmpeg = args.length > 1 ? args[1] : "ffmpeg";
        if (!Files.isRegularFile(video)) {
            throw new IOException("Need the MP4 and all frames-NNN.rgbd data files in the current directory.");
        }
        Vector<InputStream> parts = new Vector<>();
        for (int i = 0; ; i++) {
            Path part = Path.of(String.format("frames-%03d.rgbd", i));
            if (!Files.isRegularFile(part)) break;
            parts.add(new InflaterInputStream(new BufferedInputStream(Files.newInputStream(part))));
        }
        if (parts.isEmpty()) throw new IOException("No frames-NNN.rgbd pixel data files found.");

        AudioFormat format = new AudioFormat(AUDIO_RATE, 16, 2, true, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(format, 32768);
        line.start();

        Process audio = new ProcessBuilder(ffmpeg, "-v", "error", "-i", video.toString(),
                "-map", "0:a:0", "-vn", "-f", "s16le", "-acodec", "pcm_s16le",
                "-ar", Integer.toString(AUDIO_RATE), "-ac", "2", "pipe:1").redirectError(ProcessBuilder.Redirect.INHERIT).start();
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicBoolean audioDone = new AtomicBoolean(false);
        Thread audioThread = new Thread(() -> {
            try (InputStream pcm = new BufferedInputStream(audio.getInputStream())) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = pcm.read(buffer)) >= 0) if (n > 0) line.write(buffer, 0, n);
            } catch (IOException e) {
                if (running.get()) e.printStackTrace();
            } finally {
                audioDone.set(true);
                if (line.isOpen()) {
                    if (running.get()) line.drain();
                    line.stop();
                    line.close();
                }
            }
        }, "audio-decode");

        PlayerPanel panel = new PlayerPanel();
        JFrame window = new JFrame("Java Virtual Machine");
        window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        window.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) {
                running.set(false);
                audio.destroyForcibly();
                if (line.isOpen()) {
                    line.stop();
                    line.close();
                }
            }
        });
        window.setContentPane(panel);
        window.pack();
        window.setLocationRelativeTo(null);
        window.setVisible(true);
        audioThread.start();

        byte[] gray = new byte[PIXELS];
        byte[] grayDelta = new byte[PIXELS];
        int[] rgbExceptions = new int[PIXELS];
        int[] rgb = new int[PIXELS];
        long start = System.nanoTime();
        int frameNumber = 0;
        try (InputStream deltas = new SequenceInputStream(Collections.enumeration(parts))) {
            while (running.get() && readFully(deltas, grayDelta)) {
                for (int i = 0; i < PIXELS; i++) gray[i] ^= grayDelta[i];
                int exceptionCount = readUnsignedShort(deltas);
                for (int i = 0; i < exceptionCount; i++) {
                    int pixel = readInt(deltas);
                    int r = readByte(deltas), g = readByte(deltas), b = readByte(deltas);
                    rgbExceptions[pixel] = (r << 16) | (g << 8) | b;
                }
                long deadline = start + frameNumber * 1_000_000_000L / FPS;
                sleepUntil(deadline, running);
                if (!running.get()) break;
                for (int i = 0; i < PIXELS; i++) {
                    int value = gray[i] & 255;
                    rgb[i] = rgbExceptions[i] != 0
                            ? rgbExceptions[i]
                            : (value << 16) | (value << 8) | value;
                    rgbExceptions[i] = 0;
                }
                SwingUtilities.invokeAndWait(() -> {
                    if (running.get()) panel.setPixels(rgb);
                });
                frameNumber++;
            }
        }
        audioThread.join();
        int rc = audio.waitFor();
        if (rc != 0) System.err.println("ffmpeg audio decoder exited with status " + rc);
        if (!audioDone.get()) line.close();
    }

    private static boolean readFully(InputStream in, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int n = in.read(target, offset, target.length - offset);
            if (n < 0) {
                if (offset == 0) return false;
                throw new IOException("Truncated pixel-delta stream");
            }
            offset += n;
        }
        return true;
    }

    private static int readByte(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) throw new IOException("Truncated pixel exception data");
        return value;
    }

    private static int readUnsignedShort(InputStream in) throws IOException {
        return (readByte(in) << 8) | readByte(in);
    }

    private static int readInt(InputStream in) throws IOException {
        return (readByte(in) << 24) | (readByte(in) << 16) | (readByte(in) << 8) | readByte(in);
    }

    private static void sleepUntil(long deadline, AtomicBoolean running) throws InterruptedException {
        while (running.get()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return;
            Thread.sleep(remaining / 1_000_000, (int) (remaining % 1_000_000));
        }
    }

    private static final class PlayerPanel extends JPanel {
        private final BufferedImage image = new BufferedImage(
                BadApplePlayer.WIDTH, BadApplePlayer.HEIGHT, BufferedImage.TYPE_INT_RGB);
        private final int[] imagePixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        PlayerPanel() {
            setPreferredSize(new Dimension(BadApplePlayer.WIDTH * 2, BadApplePlayer.HEIGHT * 2));
        }
        void setPixels(int[] pixels) {
            System.arraycopy(pixels, 0, imagePixels, 0, pixels.length);
            repaint();
        }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            double scale = Math.min((double) getWidth() / BadApplePlayer.WIDTH,
                    (double) getHeight() / BadApplePlayer.HEIGHT);
            int drawWidth = (int) Math.round(BadApplePlayer.WIDTH * scale);
            int drawHeight = (int) Math.round(BadApplePlayer.HEIGHT * scale);
            int x = (getWidth() - drawWidth) / 2;
            int y = (getHeight() - drawHeight) / 2;
            g2.drawImage(image, x, y, drawWidth, drawHeight, null);
            g2.dispose();
        }
    }
}
