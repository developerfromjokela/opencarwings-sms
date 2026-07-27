package com.developerfromjokela.opencarwings.sms;

import com.developerfromjokela.opencarwings.sms.cli.CliRunner;
import com.developerfromjokela.opencarwings.sms.gui.GuiApp;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

public class MainApp {

    public static void main(String[] args) {
        boolean nogui = false;
        List<String> remaining = new ArrayList<>();
        for (String arg : args) {
            if (arg.equals("--nogui")) {
                nogui = true;
            } else {
                remaining.add(arg);
            }
        }

        if (nogui) {
            CliRunner.run(remaining.toArray(new String[0]));
        } else {
            SwingUtilities.invokeLater(() -> {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignored) {
                }
                new GuiApp().setVisible(true);
            });
        }
    }
}
