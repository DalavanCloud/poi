/* ====================================================================
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
==================================================================== */

package org.apache.poi.ss.util;

import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.text.AttributedString;
import java.util.Locale;
import java.util.Map;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.RichTextString;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.util.Internal;


/**
 * Helper methods for when working with Usermodel sheets
 *
 * @author Yegor Kozlov
 */
public class SheetUtil {

    /**
     * Excel measures columns in units of 1/256th of a character width
     * but the docs say nothing about what particular character is used.
     * '0' looks to be a good choice.
     */
    private static final char defaultChar = '0';

    /**
     * This is the multiple that the font height is scaled by when determining the
     * boundary of rotated text.
     */
    private static final double fontHeightMultiple = 2.0;

    /**
     *  Dummy formula evaluator that does nothing.
     *  YK: The only reason of having this class is that
     *  {@link org.apache.poi.ss.usermodel.DataFormatter#formatCellValue(org.apache.poi.ss.usermodel.Cell)}
     *  returns formula string for formula cells. Dummy evaluator makes it to format the cached formula result.
     *
     *  See Bugzilla #50021
     */
    private static final FormulaEvaluator dummyEvaluator = new FormulaEvaluator(){
        @Override
        public void clearAllCachedResultValues(){}
        @Override
        public void notifySetFormula(Cell cell) {}
        @Override
        public void notifyDeleteCell(Cell cell) {}
        @Override
        public void notifyUpdateCell(Cell cell) {}
        @Override
        public CellValue evaluate(Cell cell) {return null;  }
        @Override
        public Cell evaluateInCell(Cell cell) { return null; }
        @Override
        public void setupReferencedWorkbooks(Map<String, FormulaEvaluator> workbooks) {}
        @Override
        public void setDebugEvaluationOutputForNextEval(boolean value) {}
        @Override
        public void setIgnoreMissingWorkbooks(boolean ignore) {}
        @Override
        public void evaluateAll() {}
        @Override
        public int evaluateFormulaCell(Cell cell) {
            return cell.getCachedFormulaResultType();
        }
        /** @deprecated POI 3.15 beta 3. Will be deleted when we make the CellType enum transition. See bug 59791. */
        @Internal
        @Override
        public CellType evaluateFormulaCellEnum(Cell cell) {
            return cell.getCachedFormulaResultTypeEnum();
        }
    };

    /**
     * drawing context to measure text
     */
    private static final FontRenderContext fontRenderContext = new FontRenderContext(null, true, true);

    /**
     * Compute width of a single cell
     *
     * @param cell the cell whose width is to be calculated
     * @param defaultCharWidth the width of a single character
     * @param formatter formatter used to prepare the text to be measured
     * @param useMergedCells    whether to use merged cells
     * @return  the width in pixels or -1 if cell is empty
     */
    public static double getCellWidth(Cell cell, int defaultCharWidth, DataFormatter formatter, boolean useMergedCells) {
        Sheet sheet = cell.getSheet();
        Workbook wb = sheet.getWorkbook();
        Row row = cell.getRow();
        int column = cell.getColumnIndex();

        // FIXME: this looks very similar to getCellWithMerges below. Consider consolidating.
        // We should only be checking merged regions if useMergedCells is true. Why are we doing this for-loop?
        int colspan = 1;
        for (CellRangeAddress region : sheet.getMergedRegions()) {
            if (containsCell(region, row.getRowNum(), column)) {
                if (!useMergedCells) {
                    // If we're not using merged cells, skip this one and move on to the next.
                    return -1;
                }
                cell = row.getCell(region.getFirstColumn());
                colspan = 1 + region.getLastColumn() - region.getFirstColumn();
            }
        }

        CellStyle style = cell.getCellStyle();
        CellType cellType = cell.getCellTypeEnum();

        // for formula cells we compute the cell width for the cached formula result
        if (cellType == CellType.FORMULA)
            cellType = cell.getCachedFormulaResultTypeEnum();

        Font font = wb.getFontAt(style.getFontIndex());

        double width = -1;
        if (cellType == CellType.STRING) {
            RichTextString rt = cell.getRichStringCellValue();
            String[] lines = rt.getString().split("\\n");
            for (int i = 0; i < lines.length; i++) {
                String txt = lines[i] + defaultChar;

                AttributedString str = new AttributedString(txt);
                copyAttributes(font, str, 0, txt.length());

                if (rt.numFormattingRuns() > 0) {
                    // TODO: support rich text fragments
                }

                width = getCellWidth(defaultCharWidth, colspan, style, width, str);
            }
        } else {
            String sval = null;
            if (cellType == CellType.NUMERIC) {
                // Try to get it formatted to look the same as excel
                try {
                    sval = formatter.formatCellValue(cell, dummyEvaluator);
                } catch (Exception e) {
                    sval = String.valueOf(cell.getNumericCellValue());
                }
            } else if (cellType == CellType.BOOLEAN) {
                sval = String.valueOf(cell.getBooleanCellValue()).toUpperCase(Locale.ROOT);
            }
            if(sval != null) {
                String txt = sval + defaultChar;
                AttributedString str = new AttributedString(txt);
                copyAttributes(font, str, 0, txt.length());

                width = getCellWidth(defaultCharWidth, colspan, style, width, str);
            }
        }
        return width;
    }

    /**
     * Calculate the best-fit width for a cell
     * If a merged cell spans multiple columns, evenly distribute the column width among those columns
     *
     * @param defaultCharWidth the width of a character using the default font in a workbook
     * @param colspan the number of columns that is spanned by the cell (1 if the cell is not part of a merged region)
     * @param style the cell style, which contains text rotation and indention information needed to compute the cell width
     * @param width the minimum best-fit width. This algorithm will only return values greater than or equal to the minimum width.
     * @param str the text contained in the cell
     * @return the best fit cell width
     */
    private static double getCellWidth(int defaultCharWidth, int colspan,
            CellStyle style, double minWidth, AttributedString str) {
        TextLayout layout = new TextLayout(str.getIterator(), fontRenderContext);
        final Rectangle2D bounds;
        if(style.getRotation() != 0){
            /*
             * Transform the text using a scale so that it's height is increased by a multiple of the leading,
             * and then rotate the text before computing the bounds. The scale results in some whitespace around
             * the unrotated top and bottom of the text that normally wouldn't be present if unscaled, but
             * is added by the standard Excel autosize.
             */
            AffineTransform trans = new AffineTransform();
            trans.concatenate(AffineTransform.getRotateInstance(style.getRotation()*2.0*Math.PI/360.0));
            trans.concatenate(
            AffineTransform.getScaleInstance(1, fontHeightMultiple)
            );
            bounds = layout.getOutline(trans).getBounds();
        } else {
            bounds = layout.getBounds();
        }
        // frameWidth accounts for leading spaces which is excluded from bounds.getWidth()
        final double frameWidth = bounds.getX() + bounds.getWidth();
        final double width = Math.max(minWidth, ((frameWidth / colspan) / defaultCharWidth) + style.getIndention());
        return width;
    }

    /**
     * Compute width of a column and return the result
     *
     * @param sheet the sheet to calculate
     * @param column    0-based index of the column
     * @param useMergedCells    whether to use merged cells
     * @return  the width in pixels or -1 if all cells are empty
     */
    public static double getColumnWidth(Sheet sheet, int column, boolean useMergedCells) {
        return getColumnWidth(sheet, column, useMergedCells, sheet.getFirstRowNum(), sheet.getLastRowNum());
    }
    
    /**
     * Compute width of a column based on a subset of the rows and return the result
     *
     * @param sheet the sheet to calculate
     * @param column    0-based index of the column
     * @param useMergedCells    whether to use merged cells
     * @param firstRow  0-based index of the first row to consider (inclusive)
     * @param lastRow   0-based index of the last row to consider (inclusive)
     * @return  the width in pixels or -1 if cell is empty
     */
    public static double getColumnWidth(Sheet sheet, int column, boolean useMergedCells, int firstRow, int lastRow){
        DataFormatter formatter = new DataFormatter();
        int defaultCharWidth = getDefaultCharWidth(sheet.getWorkbook());

        double width = -1;
        for (int rowIdx = firstRow; rowIdx <= lastRow; ++rowIdx) {
            Row row = sheet.getRow(rowIdx);
            if( row != null ) {
                double cellWidth = getColumnWidthForRow(row, column, defaultCharWidth, formatter, useMergedCells);
                width = Math.max(width, cellWidth);
            }
        }
        return width;
    }

    /**
     * Get default character width using the Workbook's default font
     *
     * @param wb the workbook to get the default character width from
     * @return default character width in pixels
     */
    @Internal
    public static int getDefaultCharWidth(final Workbook wb) {
        Font defaultFont = wb.getFontAt((short) 0);

        AttributedString str = new AttributedString(String.valueOf(defaultChar));
        copyAttributes(defaultFont, str, 0, 1);
        TextLayout layout = new TextLayout(str.getIterator(), fontRenderContext);
        int defaultCharWidth = (int) layout.getAdvance();
        return defaultCharWidth;
    }

    /**
     * Compute width of a single cell in a row
     * Convenience method for {@link getCellWidth}
     *
     * @param row the row that contains the cell of interest
     * @param column the column number of the cell whose width is to be calculated
     * @param defaultCharWidth the width of a single character
     * @param formatter formatter used to prepare the text to be measured
     * @param useMergedCells    whether to use merged cells
     * @return  the width in pixels or -1 if cell is empty
     */
    private static double getColumnWidthForRow(
            Row row, int column, int defaultCharWidth, DataFormatter formatter, boolean useMergedCells) {
        if( row == null ) {
            return -1;
        }

        Cell cell = row.getCell(column);

        if (cell == null) {
            return -1;
        }

        return getCellWidth(cell, defaultCharWidth, formatter, useMergedCells);
    }

    /**
     * Check if the Fonts are installed correctly so that Java can compute the size of
     * columns. 
     * 
     * If a Cell uses a Font which is not available on the operating system then Java may 
     * fail to return useful Font metrics and thus lead to an auto-computed size of 0.
     * 
     *  This method allows to check if computing the sizes for a given Font will succeed or not.
     *
     * @param font The Font that is used in the Cell
     * @return true if computing the size for this Font will succeed, false otherwise
     */
    public static boolean canComputeColumnWidth(Font font) {
        // not sure what is the best value sample-here, only "1" did not work on some platforms...
        AttributedString str = new AttributedString("1w");
        copyAttributes(font, str, 0, "1w".length());

        TextLayout layout = new TextLayout(str.getIterator(), fontRenderContext);
        if(layout.getBounds().getWidth() > 0) {
            return true;
        }

        return false;
    }

    /**
     * Copy text attributes from the supplied Font to Java2D AttributedString
     */
    private static void copyAttributes(Font font, AttributedString str, int startIdx, int endIdx) {
        str.addAttribute(TextAttribute.FAMILY, font.getFontName(), startIdx, endIdx);
        str.addAttribute(TextAttribute.SIZE, (float)font.getFontHeightInPoints());
        if (font.getBoldweight() == Font.BOLDWEIGHT_BOLD) str.addAttribute(TextAttribute.WEIGHT, TextAttribute.WEIGHT_BOLD, startIdx, endIdx);
        if (font.getItalic() ) str.addAttribute(TextAttribute.POSTURE, TextAttribute.POSTURE_OBLIQUE, startIdx, endIdx);
        if (font.getUnderline() == Font.U_SINGLE ) str.addAttribute(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON, startIdx, endIdx);
    }

    /**
     * Check if the cell is in the specified cell range
     *
     * @param cr    the cell range to check in
     * @param rowIx the row to check
     * @param colIx the column to check
     * @return true if the range contains the cell [rowIx, colIx]
     * @deprecated 3.15 beta 2. Use {@link CellRangeAddressBase#isInRange(int, int)}.
     */
    public static boolean containsCell(CellRangeAddress cr, int rowIx, int colIx) {
        return cr.isInRange(rowIx,  colIx);
    }

    /**
     * Return the cell, taking account of merged regions. Allows you to find the
     *  cell who's contents are shown in a given position in the sheet.
     * 
     * <p>If the cell at the given co-ordinates is a merged cell, this will
     *  return the primary (top-left) most cell of the merged region.
     * <p>If the cell at the given co-ordinates is not in a merged region,
     *  then will return the cell itself.
     * <p>If there is no cell defined at the given co-ordinates, will return
     *  null.
     */
    public static Cell getCellWithMerges(Sheet sheet, int rowIx, int colIx) {
        Row r = sheet.getRow(rowIx);
        if (r != null) {
            Cell c = r.getCell(colIx);
            if (c != null) {
                // Normal, non-merged cell
                return c;
            }
        }
        
        for (CellRangeAddress mergedRegion : sheet.getMergedRegions()) {
            if (mergedRegion.isInRange(rowIx, colIx)) {
                // The cell wanted is in this merged range
                // Return the primary (top-left) cell for the range
                r = sheet.getRow(mergedRegion.getFirstRow());
                if (r != null) {
                    return r.getCell(mergedRegion.getFirstColumn());
                }
            }
        }
        
        // If we get here, then the cell isn't defined, and doesn't
        //  live within any merged regions
        return null;
    }
}
