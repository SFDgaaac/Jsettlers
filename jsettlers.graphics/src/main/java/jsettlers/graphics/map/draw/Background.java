/*******************************************************************************
 * Copyright (c) 2015 - 2017
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 *******************************************************************************/
package jsettlers.graphics.map.draw;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

import go.graphics.AdvancedUpdateBufferCache;
import go.graphics.BackgroundDrawHandle;
import go.graphics.GLDrawContext;
import go.graphics.IllegalBufferException;
import go.graphics.ImageData;
import go.graphics.TextureHandle;

import go.graphics.VkDrawContext;
import jsettlers.common.CommonConstants;
import jsettlers.common.images.ImageLink;
import jsettlers.common.landscape.ELandscapeType;
import jsettlers.common.map.IDirectGridProvider;
import jsettlers.common.map.IGraphicsBackgroundListener;
import jsettlers.common.map.shapes.MapRectangle;
import jsettlers.common.position.FloatRectangle;
import jsettlers.graphics.image.Image;
import jsettlers.graphics.image.NullImage;
import jsettlers.graphics.image.SingleImage;
import jsettlers.graphics.image.reader.translator.DatBitmapTranslator;
import jsettlers.graphics.map.MapDrawContext;
import jsettlers.graphics.image.reader.DatFileReader;
import jsettlers.graphics.image.reader.ImageArrayProvider;
import jsettlers.graphics.image.reader.ImageMetadata;

import javax.imageio.ImageIO;

/**
 * The map background.
 * <p>
 * This class draws the map background (landscape) layer. It has support for smooth FOW transitions and buffers the background to make it faster.
 *
 * @author Michael Zangl
 */
public class Background implements IGraphicsBackgroundListener {

	private static final int LAND_FILE = 0;

	/**
	 * The base texture size.
	 */
	private static final int TEXTURE_SIZE = 1024;

	/**
	 * Our base texture is divided into multiple squares that all hold a single texture. Continuous textures occupy 5*5 squares
	 */
	private static final int TEXTURE_GRID = 32;

	private static final int BYTES_PER_FIELD = 4*6*3*2; // 4 bytes per float * 6 components(x,y,z,t,v,color) * 3 points per triangle * 2 triangles per field

	/**
	 * Where are the textures on the map?
	 * <p>
	 * x and y coordinates are in Grid units.
	 * <p>
	 * The third entry is the size of the texture. It must be 1 for border tiles and 2..5 for continuous images. Always 1 more than they are wide.
	 *
	 * <pre>
	 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
	 * |0             |1             |3             |4             |5             |7             | 5| 6|
	 * +              +              +              +              +              +              +--+--+
	 * |0             |1             |3             |4             |5             |7             | 8| 9|
	 * +              +              +              +              +              +              +--+--+
	 * |0             |1             |3             |4             |5             |7             |11|12|
	 * +              +              +              +              +              +              +--+--+
	 * |0             |1             |3             |4             |5             |7             |13|14|
	 * +              +              +              +              +              +              +--+--+
	 * |0             |1             |3             |4             |5             |7             |15|16|
	 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
	 * |10            |18            |              |              |              |              |17|19|
	 * +              +              +              +              +              +              +--+--+
	 * |10            |18            |              |              |              |              |20|22|
	 * +              +              +              +              +              +              +--+--+
	 * |10            |18            |     21       |     24       |     31       |      35      |23|25|
	 * +              +              +              +              +              +              +--+--+
	 * |10            |18            |              |              |              |              |26|27|
	 * +              +              +              +              +              +              +--+--+
	 * |10            |18            |              |              |              |              |28|29|
	 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
	 * |              |              |              |              |              |              |30|32|
	 * +              +              +              +              +              +              +--+--+
	 * |              |              |              |              |              |              |33|34|
	 * +              +              +              +              +              +              +--+--+
	 * |      36      |     176      |              |              |              |              |98|99|
	 * +              +              +              +              +              +              +--+--+
	 * |              |              |              |              |              |              |37|38|
	 * +              +              +              +              +              +              +--+--+
	 * |              |              |              |              |              |              |39|40|
	 * |41|42|43|44|45|46|47|48|49|50|51|52|53|54|55|56|57|58|59|60|61|62|63|64|65|66|67|68|69|70|71|72|
	 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+ --- ↓ +100
	 * |73|74|75|76|77|78|79|80|81|82|83|84|85|86|87|88|89|90|91|92|93|94|95|96|97|00|01|02|03|04|05|06|
	 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
	 * |07|08|09|10|11|12|13|14|15|16|17|18|19|20|21|22|23|24|25|26|27|28|29|30|31|32|33|34|35|36|37|38|
	 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
	 * |39|40|41|42|43|44|45|46|47|48|49|50|51|52|53|54|55|56|57|58|59|60|61|62|63|64|65|66|67|68|69|70|
	 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+ --- ↓ +200
	 * |71|72|73|74|75|  |77|78|79|80|81|82|83|84|85|86|87|88|89|90|91|92|93|94|95|96|97|98|99|00|01|02|
	 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
	 * |     |     |     |     |     |     |     |              |              |
	 * | 011 | 012 | 013 | 014 | 015 | 016 | 017 |              |              |
	 * |     |     |     |     |     |     |     |              |     230      |
	 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+  217         |              |--+--+--+--+--+--+--+--+
	 * +03|04|05|06|07|08|09|10|11|12|13|14|15|16|              |              |--+--+--+--+--+--+--+--+
	 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+              |              |--+--+--+--+--+--+--+--+
	 * +18|19|20|21|22|23|24|25|26|27|28|29|31|32|              |              |--+--+--+--+--+--+--+--+
	 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+              |              |--+--+--+--+--+--+--+--+
	 * +33|34|                                   |              |              |--+--+--+--+--+--+--+--+
	 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
	 */
	private static final int[][] TEXTURE_POSITIONS = {
			/* 0: big */{
					0, 0, 5
			},
			/* 1: big */{
					5, 0, 5
			},
			/* 2: big */{
					10, 0, 5
			},
			/* 3: big */{
					15, 0, 5
			},
			/* 4: big */{
					20, 0, 5
			},
			/* 5: small */{
					30, 0, 1
			},
			/* 6: small */{
					31, 0, 1
			},
			/* 7: big */{
					25, 0, 5
			},
			/* 8: small */{
					30, 1, 1
			},
			/* 9: small */{
					31, 1, 1
			},
			/* 10: big */{
					0, 5, 5
			},
			/* 11: small, continuous */{
					0, 20, 2
			},
			/* 12: small, continuous */{
					2, 20, 2
			},
			/* 13: small, continuous */{
					4, 20, 2
			},
			/* 14: small, continuous */{
					6, 20, 2
			},
			/* 15: small, continuous */{
					8, 20, 2
			},
			/* 16: small, continuous */{
					10, 20, 2
			},
			/* 17: small, continuous */{
					12, 20, 2
			},
			/* 18: big */{
					5, 5, 5
			},
			/* 19: small */{
					31, 5, 1
			},
			/* 20: small */{
					30, 6, 1
			},
			/* 21: big */{
					10, 5, 5
			},
			/* 22: small */{
					31, 6, 1
			},
			/* 23: small */{
					30, 7, 1
			},
			/* 24: big */{
					15, 5, 5
			},
			/* 25: small */{
					31, 7, 1
			},
			/* 26: small */{
					30, 8, 1
			},
			/* 27: small */{
					31, 8, 1
			},
			/* 28: small */{
					30, 9, 1
			},
			/* 29: small */{
					31, 9, 1
			},
			/* 30: small */{
					30, 10, 1
			},
			/* 31: big */{
					20, 5, 5
			},
			/* 32: small */{
					31, 10, 1
			},
			/* 33: small */{
					30, 11, 1
			},
			/* 34: small */{
					31, 11, 1
			},
			/* 35: big */{
					25, 5, 5
			},
			/* 36: big */{
					0, 10, 5
			},
			/* 37: small */{
					30, 13, 1
			},
			/* 38: small */{
					31, 13, 1
			},
			/* 39: small */{
					30, 14, 1
			},
			/* 40: small */{
					31, 14, 1
			},
			/* 41: small */{
					0, 15, 1
			},
			/* 42: small */{
					1, 15, 1
			},
			/* 43: small */{
					2, 15, 1
			},
			/* 44: small */{
					3, 15, 1
			},
			/* 45: small */{
					4, 15, 1
			},
			/* 46: small */{
					5, 15, 1
			},
			/* 47: small */{
					6, 15, 1
			},
			/* 48: small */{
					7, 15, 1
			},
			/* 49: small */{
					8, 15, 1
			},
			/* 50: small */{
					9, 15, 1
			},
			/* 51: small */{
					10, 15, 1
			},
			/* 52: small */{
					11, 15, 1
			},
			/* 53: small */{
					12, 15, 1
			},
			/* 54: small */{
					13, 15, 1
			},
			/* 55: small */{
					14, 15, 1
			},
			/* 56: small */{
					15, 15, 1
			},
			/* 57: small */{
					16, 15, 1
			},
			/* 58: small */{
					17, 15, 1
			},
			/* 59: small */{
					18, 15, 1
			},
			/* 60: small */{
					19, 15, 1
			},
			/* 61: small */{
					20, 15, 1
			},
			/* 62: small */{
					21, 15, 1
			},
			/* 63: small */{
					22, 15, 1
			},
			/* 64: small */{
					23, 15, 1
			},
			/* 65: small */{
					24, 15, 1
			},
			/* 66: small */{
					25, 15, 1
			},
			/* 67: small */{
					26, 15, 1
			},
			/* 68: small */{
					27, 15, 1
			},
			/* 69: small */{
					28, 15, 1
			},
			/* 70: small */{
					29, 15, 1
			},
			/* 71: small */{
					30, 15, 1
			},
			/* 72: small */{
					31, 15, 1
			},
			// ------------------------------------
			/* 73: small */{
					0, 16, 1
			},
			/* 74: small */{
					1, 16, 1
			},
			/* 75: small */{
					2, 16, 1
			},
			/* 76: small */{
					3, 16, 1
			},
			/* 77: small */{
					4, 16, 1
			},
			/* 78: small */{
					5, 16, 1
			},
			/* 79: small */{
					6, 16, 1
			},
			/* 80: small */{
					7, 16, 1
			},
			/* 81: small */{
					8, 16, 1
			},
			/* 82: small */{
					9, 16, 1
			},
			/* 83: small */{
					10, 16, 1
			},
			/* 84: small */{
					11, 16, 1
			},
			/* 85: small */{
					12, 16, 1
			},
			/* 86: small */{
					13, 16, 1
			},
			/* 87: small */{
					14, 16, 1
			},
			/* 88: small */{
					15, 16, 1
			},
			/* 89: small */{
					16, 16, 1
			},
			/* 90: small */{
					17, 16, 1
			},
			/* 91: small */{
					18, 16, 1
			},
			/* 92: small */{
					19, 16, 1
			},
			/* 93: small */{
					20, 16, 1
			},
			/* 94: small */{
					21, 16, 1
			},
			/* 95: small */{
					22, 16, 1
			},
			/* 96: small */{
					23, 16, 1
			},
			/* 97: small */{
					24, 16, 1
			},
			/* 98: small */{
					30, 16, 1
			},
			/* 99: small */{
					31, 12, 1
			},
			/* 100: small */{
					25, 12, 1
			},
			/* 101: small */{
					26, 16, 1
			},
			/* 102: small */{
					27, 16, 1
			},
			/* 103: small */{
					28, 16, 1
			},
			/* 104: small */{
					29, 16, 1
			},
			/* 105: small */{
					30, 16, 1
			},
			/* 106: small */{
					31, 16, 1
			},
			// ------------------------------------
			/* 107: small */{
					0, 17, 1
			},
			/* 108: small */{
					1, 17, 1
			},
			/* 109: small */{
					2, 17, 1
			},
			/* 110: small */{
					3, 17, 1
			},
			/* 111: small */{
					4, 17, 1
			},
			/* 112: small */{
					5, 17, 1
			},
			/* 113: small */{
					6, 17, 1
			},
			/* 114: small */{
					7, 17, 1
			},
			/* 115: small */{
					8, 17, 1
			},
			/* 116: small */{
					9, 17, 1
			},
			/* 117: small */{
					10, 17, 1
			},
			/* 118: small */{
					11, 17, 1
			},
			/* 119: small */{
					12, 17, 1
			},
			/* 120: small */{
					13, 17, 1
			},
			/* 121: small */{
					14, 17, 1
			},
			/* 122: small */{
					15, 17, 1
			},
			/* 123: small */{
					16, 17, 1
			},
			/* 124: small */{
					17, 17, 1
			},
			/* 125: small */{
					18, 17, 1
			},
			/* 126: small */{
					19, 17, 1
			},
			/* 127: small */{
					20, 17, 1
			},
			/* 128: small */{
					21, 17, 1
			},
			/* 129: small */{
					22, 17, 1
			},
			/* 130: small */{
					23, 17, 1
			},
			/* 131: small */{
					24, 17, 1
			},
			/* 132: small */{
					25, 17, 1
			},
			/* 133: small */{
					26, 17, 1
			},
			/* 134: small */{
					27, 17, 1
			},
			/* 135: small */{
					28, 17, 1
			},
			/* 136: small */{
					29, 17, 1
			},
			/* 137: small */{
					30, 17, 1
			},
			/* 138: small */{
					31, 17, 1
			},
			// ------------------------------------
			/* 139: small */{
					0, 18, 1
			},
			/* 140: small */{
					1, 18, 1
			},
			/* 141: small */{
					2, 18, 1
			},
			/* 142: small */{
					3, 18, 1
			},
			/* 143: small */{
					4, 18, 1
			},
			/* 144: small */{
					5, 18, 1
			},
			/* 145: small */{
					6, 18, 1
			},
			/* 146: small */{
					7, 18, 1
			},
			/* 147: small */{
					8, 18, 1
			},
			/* 148: small */{
					9, 18, 1
			},
			/* 149: small */{
					10, 18, 1
			},
			/* 150: small */{
					11, 18, 1
			},
			/* 151: small */{
					12, 18, 1
			},
			/* 152: small */{
					13, 18, 1
			},
			/* 153: small */{
					14, 18, 1
			},
			/* 154: small */{
					15, 18, 1
			},
			/* 155: small */{
					16, 18, 1
			},
			/* 156: small */{
					17, 18, 1
			},
			/* 157: small */{
					18, 18, 1
			},
			/* 158: small */{
					19, 18, 1
			},
			/* 159: small */{
					20, 18, 1
			},
			/* 160: small */{
					21, 18, 1
			},
			/* 161: small */{
					22, 18, 1
			},
			/* 162: small */{
					23, 18, 1
			},
			/* 163: small */{
					24, 18, 1
			},
			/* 164: small */{
					25, 18, 1
			},
			/* 165: small */{
					26, 18, 1
			},
			/* 166: small */{
					27, 18, 1
			},
			/* 167: small */{
					28, 18, 1
			},
			/* 168: small */{
					29, 18, 1
			},
			/* 169: small */{
					30, 18, 1
			},
			/* 170: small */{
					31, 18, 1
			},
			// ------------------------------------
			/* 171: small */{
					0, 19, 1
			},
			/* 172: small */{
					1, 19, 1
			},
			/* 173: small */{
					2, 19, 1
			},
			/* 174: small */{
					3, 19, 1
			},
			/* 175: small */{
					4, 19, 1
			},
			/* 176: big (odd shape?) */{
					5, 10, 5
			},
			/* 177: small */{
					6, 19, 1
			},
			/* 178: small */{
					7, 19, 1
			},
			/* 179: small */{
					8, 19, 1
			},
			/* 180: small */{
					9, 19, 1
			},
			/* 181: small */{
					10, 19, 1
			},
			/* 182: small */{
					11, 19, 1
			},
			/* 183: small */{
					12, 19, 1
			},
			/* 184: small */{
					13, 19, 1
			},
			/* 185: small */{
					14, 19, 1
			},
			/* 186: small */{
					15, 19, 1
			},
			/* 187: small */{
					16, 19, 1
			},
			/* 188: small */{
					17, 19, 1
			},
			/* 189: small */{
					18, 19, 1
			},
			/* 190: small */{
					19, 19, 1
			},
			/* 191: small */{
					20, 19, 1
			},
			/* 192: small */{
					21, 19, 1
			},
			/* 193: small */{
					22, 19, 1
			},
			/* 194: small */{
					23, 19, 1
			},
			/* 195: small */{
					24, 19, 1
			},
			/* 196: small */{
					25, 19, 1
			},
			/* 197: small */{
					26, 19, 1
			},
			/* 198: small */{
					27, 19, 1
			},
			/* 199: small */{
					28, 19, 1
			},
			/* 200: small */{
					29, 19, 1
			},
			/* 201: small */{
					30, 19, 1
			},
			/* 202: small */{
					31, 19, 1
			},
			// ------------------------------------

			/* 203: small */{
					0, 22, 1
			},
			/* 204: small */{
					1, 22, 1
			},
			/* 205: small */{
					2, 22, 1
			},
			/* 206: small */{
					3, 22, 1
			},
			/* 207: small */{
					4, 22, 1
			},
			/* 208: small */{
					5, 22, 1
			},
			/* 209: small */{
					6, 22, 1
			},
			/* 210: small */{
					7, 22, 1
			},
			/* 211: small */{
					8, 22, 1
			},
			/* 212: small */{
					9, 22, 1
			},

			/* 213: small */{
					10, 22, 1
			},
			/* 214: small */{
					11, 22, 1
			},
			/* 215: small */{
					12, 22, 1
			},
			/* 216: small */{
					13, 22, 1
			},
			/* 217: big */{
					14, 20, 5
			},
			/* 218: small */{
					1, 23, 1
			},
			/* 219: small */{
					2, 23, 1
			},
			/* 220: small */{
					3, 23, 1
			},
			/* 221: small */{
					4, 23, 1
			},
			/* 222: small */{
					5, 23, 1
			},

			/* 223: small */{
					6, 23, 1
			},
			/* 224: small */{
					7, 23, 1
			},
			/* 225: small */{
					8, 23, 1
			},
			/* 226: small */{
					9, 23, 1
			},
			/* 227: small */{
					10, 23, 1
			},
			/* 228: small */{
					11, 23, 1
			},
			/* 229: small */{
					12, 23, 1
			},
			/* 230: big */{
					19, 20, 5
			},
			/* 231: small */{
					13, 23, 1
			},
			/* 232: small */{
					0, 24, 1
			},
			/* 233: small */{
					1, 24, 1
			},
			/* 234: small */{
					2, 24, 1
			},

			// ...
	};
	private static final ImageLink ALTERNATIVE_BACKGROUND = ImageLink.fromName("background");

	private final int bufferWidth; // in map points.
	private final int bufferHeight; // in map points.

	private static Map<Boolean, TextureHandle> textures = new HashMap<>();

	private BackgroundDrawHandle backgroundHandle = null;

	private final boolean hasdgp;
	private final IDirectGridProvider dgp;
	private boolean fowEnabled;

	private final int mapWidth, mapHeight;

	private static ImageData getTextureData(boolean original) {
		int[] data = new int[TEXTURE_SIZE * TEXTURE_SIZE];
		try {
			addTextures(data);
		} catch (IOException e) {
			e.printStackTrace();
		}

		ImageData orig = new ImageData(TEXTURE_SIZE, TEXTURE_SIZE);
		orig.getWriteData32().put(data).rewind();

		if(original) {
			return orig;
		}


		Image img = ImageProvider.getInstance().getImage(ALTERNATIVE_BACKGROUND);
		if(img instanceof NullImage || (!(img instanceof SingleImage))) {
			return orig;
		}

		ImageData alt = ((SingleImage)img).getData();

		ImageData origScaled = orig.convert(alt.getWidth(), alt.getHeight());

		IntBuffer altBfr = alt.getWriteData32();
		IntBuffer origBfr = origScaled.getReadData32();

		int size = altBfr.limit();
		for(int i = 0; i < size; i++) {
			int value = altBfr.get(i);
			if ((value & 0xFF) == 0) {
				altBfr.put(i, origBfr.get(i));
			}
		}
		altBfr.rewind();
		return alt;
	}

	private static void saveOriginal(ImageData data) {
		IntBuffer image = data.getReadData32();
		final int width = data.getWidth();
		final int height = data.getHeight();

		BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		for(int y = 0; y < height; y++) {
			for(int x = 0; x < width; x++) {
				int color = image.get(y*height+x);
				img.setRGB(x, y, color);
			}
		}
		try {
			ImageIO.write(img, "PNG", new File("background.png"));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private static TextureHandle getTextureData(GLDrawContext context, boolean original) {
		TextureHandle texture = textures.get(original);

		if (texture == null || !texture.isValid()) {
			long startTime = System.currentTimeMillis();
			ImageData data = getTextureData(original);
			texture = context.generateTexture(data, "background-" + (original?"original":"custom"));
			textures.put(original, texture);

			System.out.println("Background texture generated in " + (System.currentTimeMillis() - startTime) + "ms");
		}
		return texture;

	}

	private static TextureHandle getTextureData(GLDrawContext context) {
		return getTextureData(context, DrawConstants.FORCE_ORIGINAL);
	}

	private static class ImageWriter implements ImageArrayProvider {
		int arrayOffset;
		int cellSize;
		int maxOffset;
		int[] data;

		// nothing to do. We assume images are a rectangle and have the right size.
		@Override
		public void startImage(int width, int height) {
		}

		@Override
		public void writeLine(int[] data, int length) {
			if (arrayOffset < maxOffset) {
				for (int i = 0; i < cellSize; i++) {
					this.data[arrayOffset + i] = data[i % length];
				}
				arrayOffset += TEXTURE_SIZE;
			}
		}
	}

	/**
	 * Generates the texture data.
	 *
	 * @param data
	 *            The texture data buffer.
	 * @throws IOException
	 *            If the necessary file reader is missing
	 */
	private static void addTextures(int[] data) throws IOException {
		DatFileReader reader = ImageProvider.getInstance().getFileReader(LAND_FILE);
		if (reader == null) {
			throw new IOException("Could not get a file reader for the file.");
		}
		ImageWriter imageWriter = new ImageWriter();
		imageWriter.data = data;

		ImageMetadata meta = new ImageMetadata();

		DatBitmapTranslator<SingleImage> translator = reader.getLandscapeTranslator();

		for (int index = 0; index < TEXTURE_POSITIONS.length; index++) {
			int[] position = TEXTURE_POSITIONS[index];
			int x = position[0] * TEXTURE_GRID;
			int y = position[1] * TEXTURE_GRID;
			int start = y * TEXTURE_SIZE + x;
			int cellSize = position[2] * TEXTURE_GRID;
			int end = (y + cellSize) * TEXTURE_SIZE + x;
			imageWriter.arrayOffset = start;
			imageWriter.cellSize = cellSize;
			imageWriter.maxOffset = end;

			long dataPos = reader.readImageHeader(translator, meta, reader.getOffsetForLandscape(index));
			reader.readCompressedData(translator, meta, imageWriter, dataPos);

			// freaky stuff
			int arrayOffset = imageWriter.arrayOffset;
			int l = arrayOffset - start;
			while (arrayOffset < end) {
				System.arraycopy(data, arrayOffset - l, data, arrayOffset, cellSize);
				arrayOffset += TEXTURE_SIZE;
			}
		}
	}

	private static class TextureIntersections {
		public final ELandscapeType type1;
		public final ELandscapeType type1alt;
		public final ELandscapeType type2;
		public final int baseIndex;

		public TextureIntersections(ELandscapeType type1, ELandscapeType type2, int baseIndex) {
			this(type1, type1, type2, baseIndex);
		}

		public TextureIntersections(ELandscapeType type1, ELandscapeType type1alt, ELandscapeType type2, int baseIndex) {
			this.type1 = type1;
			this.type1alt = type1alt;
			this.type2 = type2;
			this.baseIndex = baseIndex;
		}
	}

	public Background(MapDrawContext context) {
		bufferWidth = context.getMap().getWidth()-1;
		bufferHeight = context.getMap().getHeight()-1;
		mapWidth = context.getMap().getWidth();
		mapHeight = context.getMap().getHeight();

		dgp = context.getDGP();
		hasdgp = dgp != null;

		vertexBfr = ByteBuffer.allocateDirect(BYTES_PER_FIELD * bufferHeight * bufferWidth).order(ByteOrder.nativeOrder());
		localVertexBfr = new ThreadLocal<>();
		vertexCache = new AdvancedUpdateBufferCache(this::getLocalVertexBfr, BYTES_PER_FIELD, context::getGl, () -> backgroundHandle.vertices, bufferWidth);
		asyncAccessContext = context;
	}

	private ByteBuffer getLocalVertexBfr() {
		ByteBuffer localBfr = localVertexBfr.get();
		if(localBfr == null) {
			localBfr = vertexBfr.slice().order(ByteOrder.nativeOrder());
			localVertexBfr.set(localBfr);
		}
		return localBfr;

	}

	private final MapDrawContext asyncAccessContext;

	private final TextureIntersections[] borderTextures = new TextureIntersections[] {
			new TextureIntersections(ELandscapeType.SAND, ELandscapeType.WATER1, 37),

			// TODO find use for 41
			// TODO find use for 45
			// TODO find use for 49-51 textures

			new TextureIntersections(ELandscapeType.GRASS, ELandscapeType.SAND, ELandscapeType.RIVER1, 52),
			new TextureIntersections(ELandscapeType.GRASS, ELandscapeType.SAND, ELandscapeType.RIVER2, 56),
			new TextureIntersections(ELandscapeType.GRASS, ELandscapeType.SAND, ELandscapeType.RIVER3, 60),
			new TextureIntersections(ELandscapeType.GRASS, ELandscapeType.SAND, ELandscapeType.RIVER4, 64),

			new TextureIntersections(ELandscapeType.SAND, ELandscapeType.RIVER1, 68),
			new TextureIntersections(ELandscapeType.SAND, ELandscapeType.RIVER2, 72),
			new TextureIntersections(ELandscapeType.SAND, ELandscapeType.RIVER3, 76),
			new TextureIntersections(ELandscapeType.SAND, ELandscapeType.RIVER4, 80),

			new TextureIntersections(ELandscapeType.WATER1, ELandscapeType.WATER2, 84),
			new TextureIntersections(ELandscapeType.WATER2, ELandscapeType.WATER3, 88),
			new TextureIntersections(ELandscapeType.WATER3, ELandscapeType.WATER4, 92),
			new TextureIntersections(ELandscapeType.WATER4, ELandscapeType.WATER5, 96),
			new TextureIntersections(ELandscapeType.WATER5, ELandscapeType.WATER6, 100),
			new TextureIntersections(ELandscapeType.WATER6, ELandscapeType.WATER7, 104),
			new TextureIntersections(ELandscapeType.WATER7, ELandscapeType.WATER8, 108),

			new TextureIntersections(ELandscapeType.SAND, ELandscapeType.GRASS, 112),

			new TextureIntersections(ELandscapeType.GRASS, ELandscapeType.MOUNTAINBORDEROUTER, 116),
			new TextureIntersections(ELandscapeType.MOUNTAINBORDEROUTER, ELandscapeType.MOUNTAINBORDER, 120),
			new TextureIntersections(ELandscapeType.MOUNTAIN, ELandscapeType.MOUNTAINBORDER, 124),

			new TextureIntersections(ELandscapeType.GRASS, ELandscapeType.DESERTBORDEROUTER, 128),
			new TextureIntersections(ELandscapeType.DESERTBORDEROUTER, ELandscapeType.DESERTBORDER, 132),
			new TextureIntersections(ELandscapeType.DESERT, ELandscapeType.DESERTBORDER, 136),

			new TextureIntersections(ELandscapeType.GRASS, ELandscapeType.MUDBORDEROUTER, 140),
			new TextureIntersections(ELandscapeType.MUDBORDEROUTER, ELandscapeType.MUDBORDER, 144),
			new TextureIntersections(ELandscapeType.MUD, ELandscapeType.MUDBORDER, 148),

			// TODO find use for 152

			new TextureIntersections(ELandscapeType.MOUNTAIN, ELandscapeType.SNOWBORDEROUTER, 156),
			new TextureIntersections(ELandscapeType.SNOWBORDEROUTER, ELandscapeType.SNOWBORDER, 160),
			new TextureIntersections(ELandscapeType.SNOW, ELandscapeType.SNOWBORDER, 164),

			// some original maps have this
			new TextureIntersections(ELandscapeType.MOUNTAIN, ELandscapeType.SNOW, 156),

			new TextureIntersections(ELandscapeType.GRASS, ELandscapeType.EARTH, 168),
			new TextureIntersections(ELandscapeType.GRASS, ELandscapeType.FLATTENED, 172),

			// TODO find use for 176 landscape
			// TODO find use for 177 border
			// 181 is a duplicate of 172

			new TextureIntersections(ELandscapeType.GRASS, ELandscapeType.ROAD, 185),

			// 189 is another duplicate of 172
			new TextureIntersections(ELandscapeType.GRASS, ELandscapeType.DRY_GRASS, 193),
			new TextureIntersections(ELandscapeType.GRASS, ELandscapeType.DRY_EARTH, 197),

			new TextureIntersections(ELandscapeType.GRASS, ELandscapeType.MOORBORDEROUTER, 201),
			new TextureIntersections(ELandscapeType.MOORBORDEROUTER, ELandscapeType.MOORBORDER, 205),
			new TextureIntersections(ELandscapeType.MOOR, ELandscapeType.MOORBORDER, 209),

			// TODO find use for 213
			new TextureIntersections(ELandscapeType.DESERT, ELandscapeType.SHARP_FLATTENED_DESERT, 218),
			new TextureIntersections(ELandscapeType.DESERT, ELandscapeType.FLATTENED_DESERT, 222),
			// TODO find use for 226
			new TextureIntersections(ELandscapeType.MOUNTAIN, ELandscapeType.GRAVEL, 231),

	};

	/**
	 * Draws a given map content.
	 *
	 * @param context
	 *            The context to draw at.
	 * @param screen
	 *            The area to draw
	 */
	public void drawMapContent(MapDrawContext context, FloatRectangle screen) {
		GLDrawContext gl = context.getGl();
		try {
			if(backgroundHandle == null || !backgroundHandle.isValid()) {
				generateGeometry(context);
				context.getGl().setHeightMatrix(context.getConverter().getMatrixWithHeight());
			}
		} catch (IllegalBufferException e) {
			// TODO: Create crash report.
			e.printStackTrace();
		}

		MapRectangle screenArea = context.getConverter().getMapForScreen(screen);

		updateGeometry(context, screenArea);

		backgroundHandle.texture = getTextureData(gl);

		backgroundHandle.regionCount = screenArea.getLines();
		backgroundHandle.regions = new int[backgroundHandle.regionCount*2];
		for(int i = 0; i < backgroundHandle.regionCount; i++) {
			int startX = screenArea.getLineStartX(i);
			if(startX < 0) startX = 0;

			int endX = screenArea.getLineEndX(i);
			if(endX >= bufferWidth) endX = bufferWidth;

			int y = screenArea.getLineY(i);
			if(y < 0 || y > bufferHeight) continue;

			backgroundHandle.regions[i*2] = (bufferWidth * y + startX) * 2 * 3;
			backgroundHandle.regions[i*2+1] = (endX-startX)* 2 * 3;
		}
		gl.drawBackground(backgroundHandle);
	}

	private void generateGeometry(MapDrawContext context) throws IllegalBufferException {
		int vertices = bufferWidth*bufferHeight*3*2;
		backgroundHandle = context.getGl().createBackgroundDrawCall(vertices, getTextureData(context.getGl()));

		fowEnabled = hasdgp && dgp.isFoWEnabled();

		ByteBuffer bufferLine = ByteBuffer.allocateDirect(BYTES_PER_FIELD * bufferWidth).order(ByteOrder.nativeOrder());

		for(int y = 0;y != bufferHeight;y++) {
			for(int x = 0; x != bufferWidth;x++) {
				addTrianglesToGeometry(context, bufferLine, x, y);
			}
			bufferLine.rewind();
			context.getGl().updateBufferAt(backgroundHandle.vertices, BYTES_PER_FIELD*bufferWidth*y, bufferLine);
		}

		context.getMap().setBackgroundListener(this);
	}

	private final AdvancedUpdateBufferCache vertexCache;
	private final ByteBuffer vertexBfr;
	private final ThreadLocal<ByteBuffer> localVertexBfr;

	private void updateGeometry(MapDrawContext context, MapRectangle screen) {
		fowEnabled = hasdgp && dgp.isFoWEnabled();

		try {
			int height = screen.getHeight();
			int width = screen.getWidth();
			int miny = screen.getMinY();
			int minx = screen.getMinX();
			int maxy = miny + height;

			if (maxy > bufferHeight) maxy = bufferHeight;
			if (miny < 0) miny = 0;
			int linestart = minx - (miny / 2);

			if(context.getGl() instanceof VkDrawContext) {
				vertexCache.clearCache();
			} else {
				for (int y = miny; y < maxy; y++) {
					int lineStartX = linestart + (y / 2);

					int linewidth = (width + lineStartX);
					if (linewidth >= bufferWidth) {
						linewidth = bufferWidth;
					}

					int linex = lineStartX;
					if (linex < 0) {
						linex = 0;
					}

					vertexCache.clearCacheRegion(y, linex, linewidth);
				}
			}
		} catch (IllegalBufferException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Adds the two triangles for a point to the list of verteces
	 */
	private void addTrianglesToGeometry(MapDrawContext context, ByteBuffer buffer, int x, int y) {
		addTriangleToGeometry(context, buffer, x, y, true,x*37 + y*17);
		addTriangleToGeometry(context, buffer, x, y, false, x);
	}

	private void addTriangleToGeometry(MapDrawContext context, ByteBuffer buffer, int x1, int y, boolean up, int useSecondParameter) {
		int y1 = y + (up?1:0);
		int x2 = x1 + (up?0:1);
		int y2 = y + (up?0:1);
		int x3 = x1 + 1;
		int y3 = y + (up?1:0);

		ELandscapeType leftLandscape = context.getLandscape(x1, y1);
		ELandscapeType aLandscape = context.getLandscape(x2, y2);
		ELandscapeType rightLandscape = context.getLandscape(x3, y3);

		float[] texturePos;
		int textureIndex;
		int orientationIndex = up?0:1;
		if (aLandscape == leftLandscape && aLandscape == rightLandscape) {
			textureIndex = aLandscape.getImageNumber();
			texturePos = ETextureOrientation.CONTINUOS[orientationIndex];
		} else {
			textureIndex = leftLandscape.getImageNumber();
			for(TextureIntersections intersect : borderTextures) {
				int type1count = 0;
				int type1acount = 0;
				int type2count = 0;

				if(leftLandscape == intersect.type1) type1count++;
				else if(leftLandscape == intersect.type1alt) type1acount++;

				if(aLandscape == intersect.type1) type1count++;
				else if(aLandscape == intersect.type1alt) type1acount++;

				if(rightLandscape == intersect.type1) type1count++;
				else if(rightLandscape == intersect.type1alt) type1acount++;

				if(leftLandscape == intersect.type2) type2count++;
				if(aLandscape == intersect.type2) type2count++;
				if(rightLandscape == intersect.type2) type2count++;

				if(type1count + type1acount + type2count != 3 || type1acount == 2 || type2count == 0) continue;

				textureIndex = intersect.baseIndex;
				textureIndex += (type2count==2)?2:0;
				textureIndex += useSecondParameter&1;
				break;
			}

			if (leftLandscape == rightLandscape) {
				texturePos = ETextureOrientation.ORIENTATION[orientationIndex];
			} else if (leftLandscape == aLandscape) {
				texturePos = ETextureOrientation.LEFT[orientationIndex];
			} else {
				texturePos = ETextureOrientation.RIGHT[orientationIndex];
			}
		}

		int[] positions = TEXTURE_POSITIONS[textureIndex];
		// texture position
		int addDx = 0;
		int addDy = 0;
		if (positions[2] >= 2) {
			addDx = x1 * DrawConstants.DISTANCE_X - y * DrawConstants.DISTANCE_X / 2;
			addDy = y * DrawConstants.TEXTUREUNIT_Y;
			addDx = realModulo(addDx, (positions[2] - 1) * TEXTURE_GRID);
			addDy = realModulo(addDy, (positions[2] - 1) * TEXTURE_GRID);
		}
		addDx += positions[0] * TEXTURE_GRID;
		addDy += positions[1] * TEXTURE_GRID;

		{
			// left
			float u = (texturePos[0] + addDx) / TEXTURE_SIZE;
			float v = (texturePos[1] + addDy) / TEXTURE_SIZE;
			addPointToGeometry(context, buffer, up?x2:x1, up?y2:y1, u, v);
		}
		{
			// bottom
			float u = (texturePos[2] + addDx) / TEXTURE_SIZE;
			float v = (texturePos[3] + addDy) / TEXTURE_SIZE;
			addPointToGeometry(context, buffer, up?x1:x2, up?y1:y2, u, v);
		}
		{
			// right
			float u = (texturePos[4] + addDx) / TEXTURE_SIZE;
			float v = (texturePos[5] + addDy) / TEXTURE_SIZE;
			addPointToGeometry(context, buffer, x3, y3, u, v);
		}

	}


	private void addPointToGeometry(MapDrawContext context, ByteBuffer buffer, int x, int y, float u, float v) {
		int height = context.getHeight(x, y);
		byte visibleStatus = context.getVisibleStatus(x, y);

		float color = 0;
		if (x > 0 && x < mapWidth - 2 && y > 0 && y < mapHeight - 2 && (visibleStatus > 0 || !fowEnabled)) {
			int dHeight = context.getHeight(x, y-1) - height;

			color = 0.875f + dHeight * .125f;
			if (color < 0.4f) {
				color = 0.4f;
			}
			if(fowEnabled) color *= visibleStatus / (float)CommonConstants.FOG_OF_WAR_VISIBLE;
		}

		buffer.putFloat(x);
		buffer.putFloat(y);
		buffer.putFloat(height);
		buffer.putFloat(u);
		buffer.putFloat(v);
		buffer.putFloat(color);
	}

	private static int realModulo(int number, int modulo) {
		if (number >= 0) {
			return number % modulo;
		} else {
			return number % modulo + modulo;
		}
	}

	private void updateLine(int y, int x1, int x2) {
		ByteBuffer currentVertexBuffer = getLocalVertexBfr();
		currentVertexBuffer.order(ByteOrder.nativeOrder());
		currentVertexBuffer.position((y*bufferWidth+x1)*BYTES_PER_FIELD);
		for(int i = x1; i != x2; i++) {
			addTrianglesToGeometry(asyncAccessContext, currentVertexBuffer, i, y);
		}
		vertexCache.markLine(y, x1, x2 - x1);
	}

	@Override
	public void backgroundLineChangedAt(int x, int y, int length) {
		if(y == bufferHeight) return;

		int x2 = x + length;
		if(x != 0) x = x-1;
		if(x2 < bufferWidth) x2 = x2+1;
		if(x2 > bufferWidth) x2 = bufferWidth;

		updateLine(y, x, x2);
		if (y > 0) updateLine(y - 1, x, x2);
		if (y < bufferHeight - 1) updateLine(y + 1, x, x2);
	}

	@Override
	public void fogOfWarEnabledStatusChanged(boolean enabled) {
		fowEnabled = hasdgp && enabled;
	}

	/**
	 * Invalidates the background texture.
	 */
	static void invalidateTexture() {
		textures.clear();
	}
}
