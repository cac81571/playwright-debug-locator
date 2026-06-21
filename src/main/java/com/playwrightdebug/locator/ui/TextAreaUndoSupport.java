package com.playwrightdebug.locator.ui;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.event.UndoableEditEvent;
import javax.swing.event.UndoableEditListener;
import javax.swing.text.Document;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Objects;

public final class TextAreaUndoSupport {

    private final UndoManager undoManager = new UndoManager();
    private final Action undoAction;
    private final Action redoAction;

    public TextAreaUndoSupport(JComponent component, Document document) {
        Objects.requireNonNull(component);
        Objects.requireNonNull(document);

        UndoableEditListener listener = this::recordEdit;
        document.addUndoableEditListener(listener);

        undoAction = new UndoRedoAction("元に戻す", true);
        redoAction = new UndoRedoAction("やり直し", false);

        bindShortcut(component, KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "Undo", undoAction);
        bindShortcut(component, KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "Redo", redoAction);
        bindShortcut(
                component,
                KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                "RedoShift",
                redoAction
        );

        updateState();
    }

    public Action undoAction() {
        return undoAction;
    }

    public Action redoAction() {
        return redoAction;
    }

    private void recordEdit(UndoableEditEvent event) {
        undoManager.addEdit(event.getEdit());
        updateState();
    }

    private void updateState() {
        undoAction.setEnabled(undoManager.canUndo());
        redoAction.setEnabled(undoManager.canRedo());
    }

    private static void bindShortcut(JComponent component, KeyStroke keyStroke, String name, Action action) {
        component.getInputMap().put(keyStroke, name);
        component.getActionMap().put(name, action);
    }

    private final class UndoRedoAction extends AbstractAction {

        private final boolean undo;

        private UndoRedoAction(String label, boolean undo) {
            super(label);
            this.undo = undo;
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            try {
                if (undo) {
                    undoManager.undo();
                } else {
                    undoManager.redo();
                }
            } catch (CannotUndoException | CannotRedoException ignored) {
                // stale edit stack
            }
            updateState();
        }
    }
}
