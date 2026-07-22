// Copyright Daniel Ratcliffe, 2011-2022. Do not distribute without permission.
//
// SPDX-License-Identifier: LicenseRef-CCPL

package dan200.computercraft.client.gui.widgets;

import dan200.computercraft.client.gui.ClientComputerActions;
import dan200.computercraft.client.gui.ClientComputerInput;
import dan200.computercraft.client.gui.KeyConverter;
import dan200.computercraft.client.render.RenderTypes;
import dan200.computercraft.client.render.text.FixedWidthFontRenderer;
import dan200.computercraft.core.input.UserComputerInput;
import dan200.computercraft.core.terminal.Terminal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import static dan200.computercraft.client.render.ComputerBorderRenderer.MARGIN;
import static dan200.computercraft.client.render.text.FixedWidthFontRenderer.FONT_HEIGHT;
import static dan200.computercraft.client.render.text.FixedWidthFontRenderer.FONT_WIDTH;

/**
 * A widget which renders a computer terminal and handles input events (keyboard, mouse, clipboard) and computer
 * shortcuts (terminate/shutdown/reboot).
 *
 * @see ClientComputerInput The input handler typically used with this class.
 */
public class TerminalWidget extends AbstractWidget {
    private static final Component DESCRIPTION = Component.translatable("gui.computercraft.terminal");

    private static final float TERMINATE_TIME = 0.5f;
    private static final float KEY_SUPPRESS_DELAY = 0.2f;

    private final Terminal terminal;
    private final UserComputerInput computerInput;
    private final ClientComputerActions computerActions;

    // The positions of the actual terminal
    private final int innerX;
    private final int innerY;
    private final int innerWidth;
    private final int innerHeight;

    private float terminateTimer = -1;
    private float rebootTimer = -1;
    private float shutdownTimer = -1;

    private int lastMoveX = -1;
    private int lastMoveY = -1;
    private int lastSubX = -1;
    private int lastSubY = -1;
    private boolean pointerInside = false;
    private boolean cursorHidden = false;

    /**
     * How much larger the on-screen terminal is than its base cell dimensions would suggest,
     * giving high-density programs more room to breathe.
     */
    public static final float SCREEN_SCALE = 1.25f;

    public TerminalWidget(Terminal terminal, UserComputerInput computerInput, ClientComputerActions computerActions, int x, int y) {
        this(terminal, computerInput, computerActions, x, y, SCREEN_SCALE);
    }

    /**
     * Construct a terminal widget with an explicit screen scale.
     *
     * @param terminal        The terminal to display.
     * @param computerInput   The computer to send input events to.
     * @param computerActions The computer to dispatch shortcuts (terminate/shutdown/reboot) to.
     * @param x               The x position of the widget.
     * @param y               The y position of the widget.
     * @param screenScale     How much to enlarge the widget beyond the terminal's base cell dimensions. Screens with
     *                        a fixed-size background texture (i.e. turtles) pass 1, as their layout cannot absorb a
     *                        larger terminal.
     */
    public TerminalWidget(Terminal terminal, UserComputerInput computerInput, ClientComputerActions computerActions, int x, int y, float screenScale) {
        // The widget's footprint comes from the terminal's *base* size: a terminal resized by
        // term.setResolution renders more (smaller) characters in the same space.
        super(x, y, getWidth(terminal.getBaseWidth(), screenScale), getHeight(terminal.getBaseHeight(), screenScale), DESCRIPTION);

        this.terminal = terminal;
        this.computerInput = computerInput;
        this.computerActions = computerActions;

        innerX = x + MARGIN;
        innerY = y + MARGIN;
        innerWidth = Math.round(terminal.getBaseWidth() * FONT_WIDTH * screenScale);
        innerHeight = Math.round(terminal.getBaseHeight() * FONT_HEIGHT * screenScale);
    }

    /**
     * The horizontal render scale. The widget always occupies its original footprint: if the terminal is resized
     * mid-session (e.g. {@code term.setResolution}), we render more (smaller) characters in the same space.
     *
     * @return The scale to render each character at.
     */
    private float scaleX() {
        var pixels = terminal.getWidth() * FONT_WIDTH;
        return pixels == 0 ? 1 : (float) innerWidth / pixels;
    }

    private float scaleY() {
        var pixels = terminal.getHeight() * FONT_HEIGHT;
        return pixels == 0 ? 1 : (float) innerHeight / pixels;
    }

    private int charX(double mouseX) {
        var charX = (int) ((mouseX - innerX) / (FONT_WIDTH * scaleX()));
        return Math.min(Math.max(charX, 0), terminal.getWidth() - 1);
    }

    private int charY(double mouseY) {
        var charY = (int) ((mouseY - innerY) / (FONT_HEIGHT * scaleY()));
        return Math.min(Math.max(charY, 0), terminal.getHeight() - 1);
    }

    @Override
    public boolean charTyped(char ch, int modifiers) {
        computerInput.codepointTyped(ch);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) return false;
        if (Screen.isPaste(key)) {
            paste();
            return true;
        }

        if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            switch (KeyConverter.physicalToActual(key, scancode)) {
                case GLFW.GLFW_KEY_T -> {
                    if (terminateTimer < 0) terminateTimer = 0;
                }
                case GLFW.GLFW_KEY_S -> {
                    if (shutdownTimer < 0) shutdownTimer = 0;
                }
                case GLFW.GLFW_KEY_R -> {
                    if (rebootTimer < 0) rebootTimer = 0;
                }
            }
        }

        if (key >= 0 && terminateTimer < KEY_SUPPRESS_DELAY && rebootTimer < KEY_SUPPRESS_DELAY && shutdownTimer < KEY_SUPPRESS_DELAY) {
            computerInput.keyDown(key);
        }

        return true;
    }

    private void paste() {
        computerInput.paste(Minecraft.getInstance().keyboardHandler.getClipboard());
    }

    @Override
    public boolean keyReleased(int key, int scancode, int modifiers) {
        computerInput.keyUp(key);

        switch (KeyConverter.physicalToActual(key, scancode)) {
            case GLFW.GLFW_KEY_T -> terminateTimer = -1;
            case GLFW.GLFW_KEY_R -> rebootTimer = -1;
            case GLFW.GLFW_KEY_S -> shutdownTimer = -1;
            case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL ->
                terminateTimer = rebootTimer = shutdownTimer = -1;
        }

        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!inTermRegion(mouseX, mouseY)) return false;

        computerInput.mouseClick(button + 1, charX(mouseX) + 1, charY(mouseY) + 1);

        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!inTermRegion(mouseX, mouseY)) return false;

        computerInput.mouseUp(button + 1, charX(mouseX) + 1, charY(mouseY) + 1);

        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double v2, double v3) {
        if (!inTermRegion(mouseX, mouseY)) return false;

        computerInput.mouseDrag(button + 1, charX(mouseX) + 1, charY(mouseY) + 1);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (!inTermRegion(mouseX, mouseY)) return false;
        if (deltaY == 0) return false;

        computerInput.mouseScroll(deltaY < 0 ? 1 : -1, charX(mouseX) + 1, charY(mouseY) + 1);

        return true;
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (inTermRegion(mouseX, mouseY)) {
            // Cell position, plus the teletext subpixel (2x3 per cell) within it, so pointers can
            // track the mouse at finer-than-cell precision.
            var rawX = (mouseX - innerX) / (FONT_WIDTH * scaleX());
            var rawY = (mouseY - innerY) / (FONT_HEIGHT * scaleY());
            var cellX = Math.min(Math.max((int) rawX, 0), terminal.getWidth() - 1) + 1;
            var cellY = Math.min(Math.max((int) rawY, 0), terminal.getHeight() - 1) + 1;
            var subX = Math.min(Math.max((int) ((rawX - Math.floor(rawX)) * 2), 0), 1);
            var subY = Math.min(Math.max((int) ((rawY - Math.floor(rawY)) * 3), 0), 2);
            if (!pointerInside || cellX != lastMoveX || cellY != lastMoveY || subX != lastSubX || subY != lastSubY) {
                pointerInside = true;
                lastMoveX = cellX;
                lastMoveY = cellY;
                lastSubX = subX;
                lastSubY = subY;
                computerInput.mouseMove(cellX, cellY, subX, subY);
            }
        } else if (pointerInside) {
            pointerInside = false;
            lastMoveX = lastMoveY = lastSubX = lastSubY = -1;
            computerInput.mouseLeave();
        }
        updateCursorVisibility();
    }

    /**
     * Hide the hardware cursor while it hovers the terminal of a program which draws its own pointer
     * (see {@code term.setMouseCapture}). The ordinary cursor re-appears as soon as the program stops
     * capturing or the pointer leaves the terminal.
     */
    private void updateCursorVisibility() {
        var hide = pointerInside && active && visible && terminal.getMouseCapture();
        if (hide == cursorHidden) return;
        cursorHidden = hide;
        GLFW.glfwSetInputMode(
            Minecraft.getInstance().getWindow().getWindow(), GLFW.GLFW_CURSOR,
            hide ? GLFW.GLFW_CURSOR_HIDDEN : GLFW.GLFW_CURSOR_NORMAL
        );
    }

    /**
     * Restore input state when the containing screen closes: release held inputs and un-hide the cursor.
     */
    public void onClosed() {
        computerInput.releaseInputs();
        pointerInside = false;
        lastMoveX = lastMoveY = lastSubX = lastSubY = -1;
        updateCursorVisibility();
    }

    private boolean inTermRegion(double mouseX, double mouseY) {
        return active && visible && mouseX >= innerX && mouseY >= innerY && mouseX < innerX + innerWidth && mouseY < innerY + innerHeight;
    }

    public void update() {
        if (terminateTimer >= 0 && terminateTimer < TERMINATE_TIME && (terminateTimer += 0.05f) > TERMINATE_TIME) {
            computerActions.terminate();
        }

        if (shutdownTimer >= 0 && shutdownTimer < TERMINATE_TIME && (shutdownTimer += 0.05f) > TERMINATE_TIME) {
            computerActions.shutdown();
        }

        if (rebootTimer >= 0 && rebootTimer < TERMINATE_TIME && (rebootTimer += 0.05f) > TERMINATE_TIME) {
            computerActions.reboot();
        }
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);

        if (!focused) {
            computerInput.releaseInputs();
            shutdownTimer = terminateTimer = rebootTimer = -1;
        }
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        if (!visible) return;

        var scaleX = scaleX();
        var scaleY = scaleY();

        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(innerX, innerY, 0);
        if (scaleX != 1 || scaleY != 1) pose.scale(scaleX, scaleY, 1);

        var emitter = FixedWidthFontRenderer.toVertexConsumer(pose, graphics.bufferSource().getBuffer(RenderTypes.TERMINAL));

        FixedWidthFontRenderer.drawTerminal(
            emitter,
            0, 0, terminal, MARGIN / scaleY, MARGIN / scaleY, MARGIN / scaleX, MARGIN / scaleX
        );

        pose.popPose();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }

    public static int getWidth(int termWidth) {
        return getWidth(termWidth, SCREEN_SCALE);
    }

    public static int getHeight(int termHeight) {
        return getHeight(termHeight, SCREEN_SCALE);
    }

    public static int getWidth(int termWidth, float screenScale) {
        return Math.round(termWidth * FONT_WIDTH * screenScale) + MARGIN * 2;
    }

    public static int getHeight(int termHeight, float screenScale) {
        return Math.round(termHeight * FONT_HEIGHT * screenScale) + MARGIN * 2;
    }
}
