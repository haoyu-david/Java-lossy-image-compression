import Graph.Graph;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.awt.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Main extends Application {
    private FileInputStream inputStream;
    private byte[] data;
//    private Graph beforeCompressGraph = new Graph();
//    private Graph afterCompressGraph = new Graph();
    private Graph graph = new Graph();
    private int stepNumber = 0;
    private int offset;
    private int imageSize;
    private int imageWidth;
    private int imageHeight;
    private int totalPixel;
    private int[] redColor;
    private int[] greenColor;
    private int[] blueColor;
    private double[][] dct;
    private double[][] transposeDct;
    private byte[] compressedDataInByte;
    private int[][] quantizationTable;
//    private double[][] compressCg;
//    private double[][] compressCo;
//    private double[][] compressBrightness;
//    private int[][] grayScaleRGB;
//    private int[][] ditheringMatrix;

    public static void main(String args[]) {
        Application.launch(args);
    }

    @Override
    public void start(Stage primaryStage) throws IOException {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open a file");
        fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("BMP", "*.bmp")
        );

        File file = fileChooser.showOpenDialog(primaryStage);
        if (file == null) {
            System.exit(1);
        }
        String fileExtension = file.getName().substring(file.getName().lastIndexOf(".") + 1, file.getName().length());
        if (fileExtension.equals("bmp")) {
            lossyDisplay(file, primaryStage);
        }

        primaryStage.show();
    }

    private void lossyDisplay(File file, Stage primaryStage) throws IOException {
        inputStream = new FileInputStream(file);
        data = new byte[(int)file.length()];
        inputStream.read(data);

        offset = (data[10] & 0xff) | (data[11] & 0xff)<<8 | (data[12] & 0xff)<<16 | (data[13] & 0xff)<<24;
        imageSize = (data[34] & 0xff) | (data[35] & 0xff)<<8 | (data[36] & 0xff)<<16 | (data[37] & 0xff)<<24;
        imageWidth = (data[18] & 0xff) | (data[19] & 0xff)<<8 | (data[20] & 0xff)<<16 | (data[21] & 0xff)<<24;
        imageHeight = (data[22] & 0xff) | (data[23] & 0xff)<<8 | (data[24] & 0xff)<<16 | (data[25] & 0xff)<<24;
        long width = 0x00000000FFFFFFFFL & imageWidth;
        long height = 0x00000000FFFFFFFFL & imageHeight;
        imageWidth = (int)width;
        imageHeight = (int)height;
        totalPixel = imageWidth * imageHeight;

        lossyEncode();
        lossyDecode();

        primaryStage.setTitle(".IM3 file");
        HBox root = new HBox();

        root.getChildren().addAll(graph);
        Scene scene  = new Scene(root,graph.getWidth(),graph.getHeight());
        primaryStage.setScene(scene);
    }

    private void lossyEncode() throws IOException {
        long startTime = System.nanoTime();

        // Original image
        int start = offset;
        redColor = new int[256];
        greenColor = new int[256];
        blueColor = new int[256];
        Color[][] rgb = new Color[imageWidth][imageHeight];
        double[][] brightness = new double[imageWidth][imageHeight];
        double[][] co = new double[imageWidth][imageHeight];
        double[][] cg = new double[imageWidth][imageHeight];
        for (int i = 0; i < 255; i++) {
            redColor[i] = 0;
            greenColor[i] = 0;
            blueColor[i] = 0;
        }
        for (int y = 0; y < imageHeight; y++) {
            for (int x = 0; x < imageWidth; x++) {
                int b = data[start] & 0xff;
                int g = data[start+1] & 0xff;
                int r = data[start+2] & 0xff;
                redColor[r]++;
                greenColor[g]++;
                blueColor[b]++;
                Color color = new Color(r, g, b);
                brightness[x][imageHeight-y-1] = 0.25*r + 0.5*g + 0.25*b;
                co[x][imageHeight-y-1] = 0.5*r - 0.5*b;
                cg[x][imageHeight-y-1] = (-0.25*r) + 0.5*g - 0.25*b;
                graph.setPixel(x, imageHeight-y-1, color.getRGB());
                start = start + 3;
            }
        }

        // Transform
        dct = new double[8][8];
        transposeDct = new double[8][8];
        quantizationTable = new int[8][8];

        // Build DCT and it's transpose
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                double a;
                if (i == 0) {
                    a = Math.sqrt(1.0 / 8.0);
                } else {
                    a = Math.sqrt(2.0 / 8.0);
                }
                dct[i][j] = a * Math.cos(((2*j+1)*i*Math.PI)/16);
                transposeDct[j][i] = a * Math.cos(((2*j+1)*i*Math.PI)/16);
            }
        }
        // Build quantiztion table
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                quantizationTable[i][j] = 1;
            }
        }
        for (int i = 2; i < quantizationTable.length; i++) {
            int temp = 1;
            for (int count = 0; count < (i-1); count++) {
                temp = temp * 2;
            }
            for (int j = 0; j <= i; j++) {
                quantizationTable[j][i] = temp;
                quantizationTable[i][j] = temp;
            }
        }

        for (int i = 0; i < imageWidth; i += 8) {
            for (int j = 0; j < imageHeight; j += 8) {
                // Seperate block
                double[][] tempY = new double[8][8];
                double[][] tempCo = new double[8][8];
                double[][] tempCg = new double[8][8];
                double[][] newTempY;
                double[][] newTempCo;
                double[][] newTempCg;
                for (int x = i; x < i+8; x++) {
                    for (int y = j; y < j+8; y++) {
                        tempY[x%8][y%8] = brightness[x][y];
                        tempCo[x%8][y%8] = co[x][y];
                        tempCg[x%8][y%8] = cg[x][y];
                    }
                }
                newTempY = matrixMultiple(matrixMultiple(dct, tempY), transposeDct);
                newTempCo = matrixMultiple(matrixMultiple(dct, tempCo), transposeDct);
                newTempCg = matrixMultiple(matrixMultiple(dct, tempCg), transposeDct);
                for (int x = i; x < i+8; x++) {
                    for (int y = j; y < j+8; y++) {
                        brightness[x][y] = newTempY[x%8][y%8] / quantizationTable[x%8][y%8];
                        co[x][y] = newTempCo[x%8][y%8] / quantizationTable[x%8][y%8];
                        cg[x][y] = newTempCg[x%8][y%8] / quantizationTable[x%8][y%8];
                    }
                }
            }
        }

        // Store lossless compressed data
        List<Integer> compressedData = new ArrayList<>();
        compressedData.add(imageWidth);
        compressedData.add(imageHeight);
//        for (int i = 0; i < 8; i++) {
//            for (int j = 0; j < 8; j++) {
//                compressedData.add(quantizationTable[i][j]);
//            }
//        }
        for (int i = 0; i < imageWidth; i++) {
            if ((i%8) > 2) {
                continue;
            }
            for (int j = 0; j < imageHeight; j++) {
                if ((j%8) > 2) {
                    continue;
                }
                compressedData.add((int)(brightness[i][j]));
                compressedData.add((int)(co[i][j]));
                compressedData.add((int)(cg[i][j]));
            }
        }

        compressedDataInByte = new byte[compressedData.size() * 2];
        for (int i = 0; i < compressedData.size(); i++) {
            compressedDataInByte[2 * i] = (byte) (compressedData.get(i) & 0xff);
            compressedDataInByte[2 * i + 1] = (byte) ((compressedData.get(i) >> 8) & 0xff);
        }


//        int[] compressedDataInt = new int[compressedData.size()];
//        for (int i = 0; i < compressedData.size(); i++) {
//            compressedDataInt[i] = compressedData.get(i);
//        }
//
//        ByteBuffer byteBuffer = ByteBuffer.allocate(compressedData.size() * 4);
//        IntBuffer intBuffer = byteBuffer.asIntBuffer();
//        intBuffer.put(compressedDataInt);
//        compressedDataInByte = byteBuffer.array();
        FileOutputStream fileOuputStream = new FileOutputStream("lossyCompression.IM3");
        fileOuputStream.write(compressedDataInByte);

        long endTime = System.nanoTime();
        long duration = endTime - startTime;
        System.out.println("Encode speed:" + " " + duration);
    }

    private void lossyDecode() {
        long startTime = System.nanoTime();
//        ByteBuffer byteBuffer = wrap(compressedDataInByte);
//        IntBuffer intBuffer = byteBuffer.asIntBuffer();
//        int[] decompressDataWithHeader = new int[intBuffer.limit()];
//        intBuffer.get(decompressDataWithHeader);

        int[] decompressDataWithHeader = new int[compressedDataInByte.length / 2];
        for (int i = 0; i < decompressDataWithHeader.length; i++) {
            decompressDataWithHeader[i] = ((compressedDataInByte[2 * i] & 0xff) | ((compressedDataInByte[2 * i+1] & 0xff) << 8));
            if (decompressDataWithHeader[i] > 32768) {
                decompressDataWithHeader[i] = decompressDataWithHeader[i] - 65536;
            }
        }

        imageWidth = decompressDataWithHeader[0];
        imageHeight = decompressDataWithHeader[1];
//        int count = 2;
//        for (int i = 0; i < 8; i++) {
//            for (int j = 0; j < 8; j++) {
//                quantizationTable[i][j] = decompressDataWithHeader[count++];
//            }
//        }
        int[] decompressData = new int[decompressDataWithHeader.length - 2];
        for (int i = 0; i < decompressData.length; i++) {
            decompressData[i] = decompressDataWithHeader[i+2];
        }
        int[] tempY = new int[decompressData.length / 3];
        int[] tempCo = new int[decompressData.length / 3];
        int[] tempCg = new int[decompressData.length / 3];
        int temp1 = 0;
        int temp2 = 0;
        int temp3 = 0;
        double[][] brightness = new double[imageWidth][imageHeight];
        double[][] co = new double[imageWidth][imageHeight];
        double[][] cg = new double[imageWidth][imageHeight];
        for (int i = 0; i < decompressData.length; i++) {
            if (i%3 == 0) {
                tempY[temp1] = decompressData[i];
                temp1++;
            } else if (i%3 == 1) {
                tempCo[temp2] = decompressData[i];
                temp2++;
            } else if (i%3 == 2) {
                tempCg[temp3] = decompressData[i];
                temp3++;
            }
        }
        temp1 = 0;
        for (int i = 0; i < imageWidth; i++) {
            for (int j = 0; j < imageHeight; j++) {
                if (((i%8) > 2) || ((j%8) > 2)) {
                    brightness[i][j] = 0;
                    co[i][j] = 0;
                    cg[i][j] = 0;
                } else {
                    brightness[i][j] = tempY[temp1];
                    co[i][j] = tempCo[temp1];
                    cg[i][j] = tempCg[temp1];
                    temp1++;
                }
            }
        }

        // Detransform
        for (int i = 0; i < imageWidth; i += 8) {
            for (int j = 0; j < imageHeight; j += 8) {
                // Seperate block
                double[][] tempBrightness = new double[8][8];
                double[][] tempColorOrange = new double[8][8];
                double[][] tempColorGreen = new double[8][8];
                double[][] newTempY;
                double[][] newTempCo;
                double[][] newTempCg;
                for (int x = i; x < i + 8; x++) {
                    for (int y = j; y < j + 8; y++) {
                        tempBrightness[x % 8][y % 8] = brightness[x][y] * quantizationTable[x % 8][y % 8];
                        tempColorOrange[x % 8][y % 8] = co[x][y] * quantizationTable[x % 8][y % 8];
                        tempColorGreen[x % 8][y % 8] = cg[x][y] * quantizationTable[x % 8][y % 8];
                    }
                }
                newTempY = matrixMultiple(matrixMultiple(transposeDct, tempBrightness), dct);
                newTempCo = matrixMultiple(matrixMultiple(transposeDct, tempColorOrange), dct);
                newTempCg = matrixMultiple(matrixMultiple(transposeDct, tempColorGreen), dct);
                for (int x = i; x < i + 8; x++) {
                    for (int y = j; y < j + 8; y++) {
                        brightness[x][y] = newTempY[x % 8][y % 8];
                        co[x][y] = newTempCo[x % 8][y % 8];
                        cg[x][y] = newTempCg[x % 8][y % 8];
                    }
                }
            }
        }

        // YCoCg to RGB
        for (int i = 0; i < imageWidth; i++) {
            for (int j = 0; j < imageHeight; j++) {
                int r = (int) (brightness[i][j] + co[i][j] - cg[i][j]);
                int g = (int) (brightness[i][j] + cg[i][j]);
                int b = (int) (brightness[i][j] - co[i][j] - cg[i][j]);
                if (r < 0) {
                    r = 0;
                } else if (r > 255) {
                    r = 255;
                }
                if (g < 0) {
                    g = 0;
                } else if (g > 255) {
                    g = 255;
                }
                if (b < 0) {
                    b = 0;
                } else if (b > 255) {
                    b = 255;
                }

                Color color = new Color(r, g, b);
                graph.setPixel(imageWidth + i + 10, j, color.getRGB());
            }
        }

        long endTime = System.nanoTime();
        long duration = endTime - startTime;
        System.out.println("Decode speed:" + " " + duration);
//        System.out.println("Compress Rate: " + 3.0 * totalPixel / decompressData.length);
        System.out.println("Compress Rate: " + (double)data.length / compressedDataInByte.length);
    }

    public double [][] matrixMultiple(double [][] firstMatrix, double [][] secondMatrix) {
        int row1 = firstMatrix.length;
        int col1 = firstMatrix[0].length;
        int col2 = secondMatrix[0].length;

        double [][] newMatrix = new double[row1][col2];
        for (int i = 0; i < row1; i++) {
            for (int j = 0; j < col2; j++) {
                for (int k = 0; k < col1; k++) {
                    newMatrix[i][j] = newMatrix[i][j] + firstMatrix[i][k] * secondMatrix[k][j];
                }
            }
        }
        return newMatrix;
    }
}