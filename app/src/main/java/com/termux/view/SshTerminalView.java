package com.termux.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Scroller;

import com.termux.terminal.KeyHandler;
import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.WcWidth;

import com.sshclientjr.SshTerminalSession;

public final class SshTerminalView extends View {
    public interface ModifierListener {
        void onModifierStateChanged(boolean ctrlEnabled, boolean altEnabled);
    }

    public interface KeyboardListener {
        void onKeyboardRequested(boolean imeModeEnabled);
    }

    private static final int KEY_EVENT_SOURCE_SOFT_KEYBOARD = 0;

    private SshTerminalSession session;
    private TerminalEmulator emulator;
    private TerminalRenderer renderer;
    private final GestureAndScaleRecognizer gestureRecognizer;
    private final Scroller scroller;
    private int topRow;
    private float scrollRemainder;
    private int combiningAccent;
    private ModifierListener modifierListener;
    private KeyboardListener keyboardListener;
    private boolean ctrlModifier;
    private boolean altModifier;
    private boolean imeModeEnabled;
    private boolean selecting;
    private boolean draggingStartHandle;
    private boolean draggingEndHandle;
    private int selectionStartRow = -1;
    private int selectionStartColumn = -1;
    private int selectionEndRow = -1;
    private int selectionEndColumn = -1;
    private final Paint selectionHandleFillPaint;
    private final Paint selectionHandleStrokePaint;
    private final float selectionHandleRadius;
    private final float selectionHandleTouchRadius;
    private final float selectionHandleStemHeight;
    private final float selectionHandleAutoScrollEdge;

    public SshTerminalView(Context context) {
        this(context, null);
    }

    public SshTerminalView(Context context, AttributeSet attrs) {
        super(context, attrs);
        int textSizePx = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                14,
                getResources().getDisplayMetrics()
        );
        renderer = new TerminalRenderer(textSizePx, Typeface.MONOSPACE);
        scroller = new Scroller(context);
        selectionHandleRadius = dpToPx(7f);
        selectionHandleTouchRadius = dpToPx(18f);
        selectionHandleStemHeight = dpToPx(10f);
        selectionHandleAutoScrollEdge = dpToPx(28f);
        selectionHandleFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectionHandleFillPaint.setColor(0xFF39C07F);
        selectionHandleStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectionHandleStrokePaint.setColor(0xFF08130E);
        selectionHandleStrokePaint.setStyle(Paint.Style.STROKE);
        selectionHandleStrokePaint.setStrokeWidth(dpToPx(1.5f));
        setFocusable(true);
        setFocusableInTouchMode(true);

        gestureRecognizer = new GestureAndScaleRecognizer(context, new GestureAndScaleRecognizer.Listener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (selecting) {
                    clearSelection();
                    return true;
                }
                requestFocus();
                if (emulator != null && emulator.isMouseTrackingActive()) {
                    sendMouseEventCode(e, TerminalEmulator.MOUSE_LEFT_BUTTON, true);
                    sendMouseEventCode(e, TerminalEmulator.MOUSE_LEFT_BUTTON, false);
                    return true;
                }
                showKeyboard();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                return selectWordAt(e);
            }

            @Override
            public boolean onScroll(MotionEvent e2, float dx, float dy) {
                if (emulator == null) return true;
                dy += scrollRemainder;
                int deltaRows = (int) (dy / renderer.mFontLineSpacing);
                scrollRemainder = dy - deltaRows * renderer.mFontLineSpacing;
                doScroll(e2, deltaRows);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e, float velocityX, float velocityY) {
                if (emulator == null || !scroller.isFinished()) return true;
                scroller.fling(0, topRow, 0, -(int) (velocityY * 0.25f), 0, 0,
                        -emulator.getScreen().getActiveTranscriptRows(), 0);
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (scroller.isFinished()) return;
                        boolean more = scroller.computeScrollOffset();
                        topRow = scroller.getCurrY();
                        invalidate();
                        if (more) post(this);
                    }
                });
                return true;
            }

            @Override
            public boolean onScale(float focusX, float focusY, float scale) {
                return false;
            }

            @Override
            public boolean onDown(float x, float y) {
                return false;
            }

            @Override
            public boolean onUp(MotionEvent e) {
                scrollRemainder = 0f;
                return false;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (emulator == null) return;
                int[] columnAndRow = getColumnAndRow(e, true);
                selecting = true;
                draggingStartHandle = false;
                draggingEndHandle = true;
                selectionStartColumn = columnAndRow[0];
                selectionStartRow = columnAndRow[1];
                selectionEndColumn = columnAndRow[0];
                selectionEndRow = columnAndRow[1];
                invalidate();
            }
        });
    }

    public void attachSession(SshTerminalSession newSession) {
        session = newSession;
        emulator = newSession == null ? null : newSession.getEmulator();
        topRow = 0;
        updateSize();
        invalidate();
    }

    public void onScreenUpdated() {
        if (emulator == null) return;
        int rowsInHistory = emulator.getScreen().getActiveTranscriptRows();
        if (topRow < -rowsInHistory) {
            topRow = -rowsInHistory;
        }
        if (topRow != 0) {
            topRow = 0;
        }
        emulator.clearScrollCounter();
        invalidate();
    }

    public void setCtrlModifier(boolean enabled) {
        ctrlModifier = enabled;
        notifyModifierStateChanged();
    }

    public void setAltModifier(boolean enabled) {
        altModifier = enabled;
        notifyModifierStateChanged();
    }

    public void setModifierListener(ModifierListener listener) {
        modifierListener = listener;
    }

    public void setKeyboardListener(KeyboardListener listener) {
        keyboardListener = listener;
    }

    public void setImeModeEnabled(boolean enabled) {
        imeModeEnabled = enabled;
    }

    public void clearSelection() {
        selecting = false;
        draggingStartHandle = false;
        draggingEndHandle = false;
        selectionStartRow = -1;
        selectionStartColumn = -1;
        selectionEndRow = -1;
        selectionEndColumn = -1;
        invalidate();
    }

    public String getSelectedText() {
        if (!selecting || emulator == null) {
            return null;
        }
        int[] selection = getNormalizedSelection();
        return emulator.getSelectedText(selection[2], selection[0], selection[3], selection[1]);
    }

    public void pasteText(String text) {
        if (emulator != null && text != null) {
            emulator.paste(text);
        }
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        if (imeModeEnabled) {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_NORMAL
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        } else {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        }
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        return new BaseInputConnection(this, true) {
            @Override
            public boolean finishComposingText() {
                sendTextToTerminal(getEditable());
                getEditable().clear();
                return true;
            }

            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                Editable editable = getEditable();
                super.commitText(text, newCursorPosition);
                sendTextToTerminal(editable);
                editable.clear();
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int leftLength, int rightLength) {
                KeyEvent deleteKey = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL);
                for (int i = 0; i < leftLength; i++) {
                    sendKeyEvent(deleteKey);
                }
                return super.deleteSurroundingText(leftLength, rightLength);
            }

            private void sendTextToTerminal(CharSequence text) {
                int length = text.length();
                for (int i = 0; i < length; i++) {
                    char firstChar = text.charAt(i);
                    int codePoint;
                    if (Character.isHighSurrogate(firstChar)) {
                        if (++i < length) {
                            codePoint = Character.toCodePoint(firstChar, text.charAt(i));
                        } else {
                            codePoint = TerminalEmulator.UNICODE_REPLACEMENT_CHAR;
                        }
                    } else {
                        codePoint = firstChar;
                    }
                    if (codePoint == '\n') {
                        codePoint = '\r';
                    }
                    inputCodePoint(KEY_EVENT_SOURCE_SOFT_KEYBOARD, codePoint, false, false);
                }
            }
        };
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (session == null || emulator == null) return true;
        if (event.isSystem() && keyCode != KeyEvent.KEYCODE_BACK) {
            return super.onKeyDown(keyCode, event);
        } else if (event.getAction() == KeyEvent.ACTION_MULTIPLE && keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            session.write(event.getCharacters());
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_LANGUAGE_SWITCH) {
            return super.onKeyDown(keyCode, event);
        }

        boolean controlDown = event.isCtrlPressed() || ctrlModifier;
        boolean leftAltDown = ((event.getMetaState() & KeyEvent.META_ALT_LEFT_ON) != 0) || altModifier;
        boolean shiftDown = event.isShiftPressed();
        boolean rightAltDown = (event.getMetaState() & KeyEvent.META_ALT_RIGHT_ON) != 0;

        int keyMod = 0;
        if (controlDown) keyMod |= KeyHandler.KEYMOD_CTRL;
        if (event.isAltPressed() || leftAltDown) keyMod |= KeyHandler.KEYMOD_ALT;
        if (shiftDown) keyMod |= KeyHandler.KEYMOD_SHIFT;
        if (event.isNumLockOn()) keyMod |= KeyHandler.KEYMOD_NUM_LOCK;
        if (!event.isFunctionPressed() && handleKeyCode(keyCode, keyMod)) {
            return true;
        }

        int bitsToClear = KeyEvent.META_CTRL_MASK;
        if (!rightAltDown) {
            bitsToClear |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
        }
        int effectiveMetaState = event.getMetaState() & ~bitsToClear;
        if (shiftDown) {
            effectiveMetaState |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
        }

        int result = event.getUnicodeChar(effectiveMetaState);
        if (result == 0) return false;

        int oldCombiningAccent = combiningAccent;
        if ((result & KeyCharacterMap.COMBINING_ACCENT) != 0) {
            if (combiningAccent != 0) {
                inputCodePoint(event.getDeviceId(), combiningAccent, controlDown, leftAltDown);
            }
            combiningAccent = result & KeyCharacterMap.COMBINING_ACCENT_MASK;
        } else {
            if (combiningAccent != 0) {
                int combinedChar = KeyCharacterMap.getDeadChar(combiningAccent, result);
                if (combinedChar > 0) result = combinedChar;
                combiningAccent = 0;
            }
            inputCodePoint(event.getDeviceId(), result, controlDown, leftAltDown);
        }
        if (combiningAccent != oldCombiningAccent) invalidate();
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return event.isSystem() ? super.onKeyUp(keyCode, event) : true;
    }

    public void inputCodePoint(int eventSource, int codePoint, boolean controlDown, boolean leftAltDown) {
        if (session == null) return;

        boolean usedCtrlToggle = ctrlModifier;
        boolean usedAltToggle = altModifier;
        controlDown = controlDown || ctrlModifier;
        leftAltDown = leftAltDown || altModifier;

        if (controlDown) {
            if (codePoint >= 'a' && codePoint <= 'z') {
                codePoint = codePoint - 'a' + 1;
            } else if (codePoint >= 'A' && codePoint <= 'Z') {
                codePoint = codePoint - 'A' + 1;
            } else if (codePoint == ' ' || codePoint == '2') {
                codePoint = 0;
            } else if (codePoint == '[' || codePoint == '3') {
                codePoint = 27;
            } else if (codePoint == '\\' || codePoint == '4') {
                codePoint = 28;
            } else if (codePoint == ']' || codePoint == '5') {
                codePoint = 29;
            } else if (codePoint == '^' || codePoint == '6') {
                codePoint = 30;
            } else if (codePoint == '_' || codePoint == '7' || codePoint == '/') {
                codePoint = 31;
            } else if (codePoint == '8') {
                codePoint = 127;
            }
        }

        if (codePoint > -1) {
            if (eventSource > KEY_EVENT_SOURCE_SOFT_KEYBOARD) {
                switch (codePoint) {
                    case 0x02DC:
                        codePoint = 0x007E;
                        break;
                    case 0x02CB:
                        codePoint = 0x0060;
                        break;
                    case 0x02C6:
                        codePoint = 0x005E;
                        break;
                    default:
                        break;
                }
            }
            session.writeCodePoint(leftAltDown, codePoint);
            if (usedCtrlToggle || usedAltToggle) {
                ctrlModifier = false;
                altModifier = false;
                notifyModifierStateChanged();
            }
        }
    }

    public boolean handleKeyCode(int keyCode, int keyMod) {
        if (session == null || emulator == null) return false;
        String code = KeyHandler.getCode(keyCode, keyMod,
                emulator.isCursorKeysApplicationMode(),
                emulator.isKeypadApplicationMode());
        if (code == null) return false;
        session.write(code);
        return true;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateSize();
    }

    public void updateSize() {
        if (session == null) return;
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        if (viewWidth == 0 || viewHeight == 0) return;

        int columns = Math.max(4, (int) (viewWidth / renderer.mFontWidth));
        int rows = Math.max(4, (viewHeight - renderer.mFontLineSpacingAndAscent) / renderer.mFontLineSpacing);
        session.updateSize(columns, rows, (int) renderer.getFontWidth(), renderer.getFontLineSpacing());
        emulator = session.getEmulator();
        topRow = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (emulator == null) {
            canvas.drawColor(0xFF000000);
        } else {
            int[] selection = getNormalizedSelection();
            renderer.render(emulator, canvas, topRow, selection[0], selection[1], selection[2], selection[3]);
            if (selection[0] >= 0) {
                drawSelectionHandles(canvas, selection);
            }
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (emulator != null && event.isFromSource(InputDevice.SOURCE_MOUSE) && event.getAction() == MotionEvent.ACTION_SCROLL) {
            boolean up = event.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0.0f;
            doScroll(event, up ? -3 : 3);
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (emulator == null) return true;
        if (selecting) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    HandleHit handleHit = findHandleHit(event);
                    draggingStartHandle = handleHit == HandleHit.START;
                    draggingEndHandle = handleHit == HandleHit.END;
                    if (draggingStartHandle || draggingEndHandle) {
                        invalidate();
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (draggingStartHandle || draggingEndHandle) {
                        int[] columnAndRow = getColumnAndRow(event, true);
                        if (draggingStartHandle) {
                            selectionStartColumn = columnAndRow[0];
                            selectionStartRow = columnAndRow[1];
                        } else {
                            selectionEndColumn = columnAndRow[0];
                            selectionEndRow = columnAndRow[1];
                        }
                        maybeAutoScroll(event);
                        invalidate();
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (draggingStartHandle || draggingEndHandle) {
                        draggingStartHandle = false;
                        draggingEndHandle = false;
                        invalidate();
                        return true;
                    }
                    break;
                default:
                    break;
            }
        }
        gestureRecognizer.onTouchEvent(event);
        return true;
    }

    public void showKeyboard() {
        if (keyboardListener != null) {
            keyboardListener.onKeyboardRequested(imeModeEnabled);
        }
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.restartInput(this);
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private int[] getColumnAndRow(MotionEvent event, boolean relativeToScroll) {
        int column = Math.max(0, (int) (event.getX() / renderer.mFontWidth));
        int row = Math.max(0, (int) ((event.getY() - renderer.mFontLineSpacingAndAscent) / renderer.mFontLineSpacing));
        if (emulator != null) {
            column = Math.min(column, Math.max(0, emulator.mColumns - 1));
            row = Math.min(row, Math.max(0, emulator.mRows - 1));
        }
        if (relativeToScroll) {
            row += topRow;
        }
        return new int[]{column, row};
    }

    private void sendMouseEventCode(MotionEvent event, int button, boolean pressed) {
        int[] columnAndRow = getColumnAndRow(event, false);
        emulator.sendMouseEvent(button, columnAndRow[0] + 1, columnAndRow[1] + 1, pressed);
    }

    private boolean selectWordAt(MotionEvent event) {
        int[] columnAndRow = getColumnAndRow(event, true);
        return selectWordAt(columnAndRow[0], columnAndRow[1]);
    }

    private boolean selectWordAt(int column, int transcriptRow) {
        if (emulator == null) {
            return false;
        }

        TerminalBuffer screen = emulator.getScreen();
        TerminalRow row = screen.allocateFullLineIfNecessary(transcriptRow);
        int normalizedColumn = normalizeColumn(row, column);
        int codePoint = getCodePointAtColumn(row, normalizedColumn);
        if (codePoint < 0 || Character.isWhitespace(codePoint)) {
            clearSelection();
            return true;
        }

        int startColumn = normalizedColumn;
        int endColumn = normalizedColumn + Math.max(0, getCodePointWidth(codePoint) - 1);

        if (isWordCodePoint(codePoint)) {
            while (startColumn > 0) {
                int previousColumn = normalizeColumn(row, startColumn - 1);
                int previousCodePoint = getCodePointAtColumn(row, previousColumn);
                if (!isWordCodePoint(previousCodePoint)) {
                    break;
                }
                startColumn = previousColumn;
            }

            while (endColumn < emulator.mColumns - 1) {
                int nextColumn = endColumn + 1;
                int nextCodePoint = getCodePointAtColumn(row, nextColumn);
                if (!isWordCodePoint(nextCodePoint)) {
                    break;
                }
                endColumn = normalizeColumn(row, nextColumn) + Math.max(0, getCodePointWidth(nextCodePoint) - 1);
            }
        }

        selecting = true;
        draggingStartHandle = false;
        draggingEndHandle = false;
        selectionStartColumn = startColumn;
        selectionStartRow = transcriptRow;
        selectionEndColumn = Math.min(emulator.mColumns - 1, endColumn);
        selectionEndRow = transcriptRow;
        invalidate();
        return true;
    }

    private int[] getNormalizedSelection() {
        if (!selecting || selectionStartRow < 0 || selectionEndRow < 0) {
            return new int[]{-1, -1, -1, -1};
        }
        int startRow = selectionStartRow;
        int startColumn = selectionStartColumn;
        int endRow = selectionEndRow;
        int endColumn = selectionEndColumn;
        if (startRow > endRow || (startRow == endRow && startColumn > endColumn)) {
            int tempRow = startRow;
            int tempColumn = startColumn;
            startRow = endRow;
            startColumn = endColumn;
            endRow = tempRow;
            endColumn = tempColumn;
        }
        return new int[]{startRow, endRow, startColumn, endColumn};
    }

    private int normalizeColumn(TerminalRow row, int column) {
        int normalized = Math.max(0, Math.min(column, Math.max(0, emulator.mColumns - 1)));
        while (normalized > 0 && row.findStartOfColumn(normalized) == row.findStartOfColumn(normalized - 1)) {
            normalized--;
        }
        return normalized;
    }

    private int getCodePointAtColumn(TerminalRow row, int column) {
        int normalizedColumn = normalizeColumn(row, column);
        int charIndex = row.findStartOfColumn(normalizedColumn);
        int spaceUsed = row.getSpaceUsed();
        if (charIndex < 0 || charIndex >= spaceUsed || charIndex >= row.mText.length) {
            return -1;
        }
        return Character.codePointAt(row.mText, charIndex, Math.min(spaceUsed, row.mText.length));
    }

    private int getCodePointWidth(int codePoint) {
        return Math.max(1, WcWidth.width(codePoint));
    }

    private boolean isWordCodePoint(int codePoint) {
        return codePoint > 0 && (
                Character.isLetterOrDigit(codePoint)
                        || codePoint == '_'
                        || codePoint == '-'
                        || codePoint == '.'
                        || codePoint == '/'
                        || codePoint == '~'
                        || codePoint == ':'
                        || codePoint == '@'
                        || codePoint == '+'
        );
    }

    private void drawSelectionHandles(Canvas canvas, int[] selection) {
        drawHandle(canvas, selection[2], selection[0], true);
        drawHandle(canvas, selection[3], selection[1], false);
    }

    private void drawHandle(Canvas canvas, int column, int transcriptRow, boolean startHandle) {
        float[] handlePosition = getHandlePosition(column, transcriptRow, startHandle);
        if (handlePosition == null) {
            return;
        }
        float stemTop = handlePosition[1] - selectionHandleStemHeight;
        canvas.drawLine(handlePosition[0], stemTop, handlePosition[0], handlePosition[1], selectionHandleFillPaint);
        canvas.drawCircle(handlePosition[0], handlePosition[1], selectionHandleRadius, selectionHandleFillPaint);
        canvas.drawCircle(handlePosition[0], handlePosition[1], selectionHandleRadius, selectionHandleStrokePaint);
    }

    private float[] getHandlePosition(int column, int transcriptRow, boolean startHandle) {
        if (emulator == null) {
            return null;
        }
        int screenRow = transcriptRow - topRow;
        if (screenRow < 0 || screenRow >= emulator.mRows) {
            return null;
        }
        float x = (startHandle ? column : column + 1) * renderer.mFontWidth;
        x = Math.max(selectionHandleRadius, Math.min(getWidth() - selectionHandleRadius, x));
        float y = (screenRow + 1) * renderer.mFontLineSpacing;
        return new float[]{x, y};
    }

    private HandleHit findHandleHit(MotionEvent event) {
        int[] selection = getNormalizedSelection();
        if (selection[0] < 0) {
            return HandleHit.NONE;
        }

        float[] startHandle = getHandlePosition(selection[2], selection[0], true);
        if (startHandle != null && distanceSquared(event.getX(), event.getY(), startHandle[0], startHandle[1]) <= selectionHandleTouchRadius * selectionHandleTouchRadius) {
            return HandleHit.START;
        }

        float[] endHandle = getHandlePosition(selection[3], selection[1], false);
        if (endHandle != null && distanceSquared(event.getX(), event.getY(), endHandle[0], endHandle[1]) <= selectionHandleTouchRadius * selectionHandleTouchRadius) {
            return HandleHit.END;
        }

        return HandleHit.NONE;
    }

    private void maybeAutoScroll(MotionEvent event) {
        if (emulator == null) {
            return;
        }
        int minTopRow = -emulator.getScreen().getActiveTranscriptRows();
        if (event.getY() < selectionHandleAutoScrollEdge && topRow > minTopRow) {
            topRow--;
        } else if (event.getY() > getHeight() - selectionHandleAutoScrollEdge && topRow < 0) {
            topRow++;
        }
    }

    private float distanceSquared(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return dx * dx + dy * dy;
    }

    private float dpToPx(float dp) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                getResources().getDisplayMetrics()
        );
    }

    private void doScroll(MotionEvent event, int rowsDown) {
        boolean up = rowsDown < 0;
        int amount = Math.abs(rowsDown);
        for (int i = 0; i < amount; i++) {
            if (emulator.isMouseTrackingActive()) {
                sendMouseEventCode(event,
                        up ? TerminalEmulator.MOUSE_WHEELUP_BUTTON : TerminalEmulator.MOUSE_WHEELDOWN_BUTTON,
                        true);
            } else if (emulator.isAlternateBufferActive()) {
                handleKeyCode(up ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN, 0);
            } else {
                topRow = Math.min(0, Math.max(-emulator.getScreen().getActiveTranscriptRows(), topRow + (up ? -1 : 1)));
                if (!awakenScrollBars()) {
                    invalidate();
                }
            }
        }
    }

    private void notifyModifierStateChanged() {
        if (modifierListener != null) {
            modifierListener.onModifierStateChanged(ctrlModifier, altModifier);
        }
    }

    private enum HandleHit {
        NONE,
        START,
        END
    }
}
