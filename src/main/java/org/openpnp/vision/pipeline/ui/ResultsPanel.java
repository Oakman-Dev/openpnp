package org.openpnp.vision.pipeline.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Point;
import java.awt.Robot;
import java.awt.event.ActionEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSplitPane;
import javax.swing.JTextPane;
import javax.swing.JToolBar;
import javax.swing.SwingConstants;

import org.opencv.core.Core;
import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
import org.opencv.core.RotatedRect;
import org.opencv.imgproc.Imgproc;
import org.openpnp.gui.support.Icons;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.vision.FluentCv.ColorCode;
import org.openpnp.vision.FluentCv.ColorSpace;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.CvStage.Result;
import org.openpnp.vision.pipeline.CvStage.Result.Circle;
import org.pmw.tinylog.Logger;

public class ResultsPanel extends JPanel {
    private final CvPipelineEditor editor;

    private CvStage selectedStage;
    private CvStage pinnedStage;

    private Robot robot;
    
    private boolean displayTrueColors = true;
    
    
    public ResultsPanel(CvPipelineEditor editor) {
        this.editor = editor;
        
        try {
            robot = new Robot();
        }
        catch (Exception e) {
            
        }

        JSplitPane splitPane = new JSplitPane();
        splitPane.setContinuousLayout(true);
        splitPane.setOrientation(JSplitPane.VERTICAL_SPLIT);

        setLayout(new BorderLayout(0, 0));

        JPanel headerPanel = new JPanel();
        add(headerPanel, BorderLayout.NORTH);
        headerPanel.setLayout(new BorderLayout(0, 0));

        resultStageNameLabel = new JLabel("New label");
        headerPanel.add(resultStageNameLabel, BorderLayout.NORTH);
        resultStageNameLabel.setHorizontalAlignment(SwingConstants.CENTER);

        JPanel panel = new JPanel();
        headerPanel.add(panel, BorderLayout.SOUTH);

        JToolBar toolBar = new JToolBar();
        panel.add(toolBar);

        JButton firstResultButton = new JButton(firstResultAction);
        firstResultButton.setHideActionText(true);
        toolBar.add(firstResultButton);

        JButton previousResultButton = new JButton(previousResultAction);
        previousResultButton.setHideActionText(true);
        toolBar.add(previousResultButton);

        JButton nextResultButton = new JButton(nextResultAction);
        toolBar.add(nextResultButton);

        JButton lastResultButton = new JButton(lastResultAction);
        lastResultButton.setHideActionText(true);
        toolBar.add(lastResultButton);

        toolBar.addSeparator();

        JButton pinResultButton = new JButton(pinResultAction);
        pinResultButton.setHideActionText(true);
        toolBar.add(pinResultButton);
        
        JSeparator separator = new JSeparator();
        separator.setOrientation(SwingConstants.VERTICAL);
        toolBar.add(separator);
        
        JButton colorResultButton = new JButton(colorResultAction);
        colorResultButton.setHideActionText(true);
        toolBar.add(colorResultButton);
        

        JPanel modelPanel = new JPanel();
        modelPanel.setLayout(new BorderLayout(0, 0));

        modelTextPane = new JTextPane();
        modelPanel.add(new JScrollPane(modelTextPane));

        add(splitPane, BorderLayout.CENTER);
        splitPane.setRightComponent(modelPanel);
        splitPane.setDividerLocation(200);

        JPanel panel_1 = new JPanel();
        panel_1.setLayout(new BorderLayout(0, 0));

        matView = new MatView();
        panel_1.add(matView, BorderLayout.CENTER);
        splitPane.setLeftComponent(panel_1);

        JLabel matStatusLabel = new JLabel(" ");
        panel_1.add(matStatusLabel, BorderLayout.SOUTH);
        
        matView.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                Color color = robot.getPixelColor(e.getXOnScreen(), e.getYOnScreen());
                float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
                Point p = matView.scalePoint(e.getPoint());
                Object model = getModelAtPoint(p);
                if (model != null) {
                    matStatusLabel.setText(model.toString());
                }
                else {
                    LengthUnit lengthUnit = selectedStage != null ? selectedStage.getLengthUnit() : null;
                    String auxCoords = "";
                    if (lengthUnit != null) {
                        // the stage defines its proper LengthUnit 
                        BufferedImage image = matView.getImage();
                        if (image != null) {
                            Camera camera = (Camera) editor.getPipeline().getProperty("camera");
                            if (camera != null) {
                                // convert camera (pixels) to stage units 
                                Location unitsPerPixel = camera.getUnitsPerPixelAtZ()
                                        .convertToUnits(lengthUnit);
                                Location mouseLocation = unitsPerPixel.multiply(p.x-image.getWidth()/2, -p.y+image.getHeight()/2, 0, 0);
                                auxCoords = String.format(" (%f, %f %s)", mouseLocation.getX(), mouseLocation.getY(), lengthUnit.getShortName()); 
                            }
                        }
                    }
                    
                    matStatusLabel.setText(String.format("RGB: %03d, %03d, %03d HSV(full): %03d, %03d, %03d XY: %d, %d %s",
                            color.getRed(),
                            color.getGreen(),
                            color.getBlue(),
                            (int) (255.999 * hsb[0]),
                            (int) (255.999 * hsb[1]),
                            (int) (255.999 * hsb[2]),
                            p.x, 
                            p.y,
                            auxCoords));
                }
            }
        });

        addHierarchyListener(new HierarchyListener() {
            @Override
            public void hierarchyChanged(HierarchyEvent e) {
                splitPane.setDividerLocation(0.8);
            }
        });
    }

    public void refresh() {
        List<CvStage> stages = editor.getPipeline().getStages();
        // If there are no stages we can't have anything selected, so clear it.
        if (stages.isEmpty()) {
            selectedStage = null;
            pinnedStage = null;
        }
        // Otherwise if nothing is selected or if the previously selected stage is no longer in the
        // pipeline replace the selection with the first stage.
        else if (selectedStage == null || !stages.contains(selectedStage)) {
            selectedStage = stages.get(0);
            pinnedStage = null;
        }
        else if (!stages.contains(pinnedStage)) {
            pinnedStage = null;
        }
        updateAllEverything();
    }

    public void setSelectedStage(CvStage stage) {
        this.selectedStage = stage;
        updateAllEverything();
    }

    private void updateAllEverything() {
        List<CvStage> stages = editor.getPipeline().getStages();

        CvStage displayStage = (pinnedStage != null) ? pinnedStage : selectedStage;

        Result result = null;
        Mat image = null;
        Object model = null;
        if (displayStage != null) {
            result = editor.getPipeline().getResult(displayStage);
            if (result != null) {
                if (result.image != null) {
                    image = result.image.clone();
                    if (displayTrueColors) {
                        ColorSpace colorSpace = result.getColorSpace();
                        if (colorSpace != null) {
                            if ((image.channels() == 3) || (colorSpace == ColorSpace.Gray)) {
                                switch (colorSpace) {
                                    case Gray :
                                    case Bgr :
                                        //format is already ok for display
                                        break;
                                    case Rgb :
                                        //need to swap channels 0 and 2 to get to Bgr format
                                        Mat rChannel = new Mat();
                                        Mat bChannel = new Mat();
                                        Core.extractChannel(image, rChannel, 0);
                                        Core.extractChannel(image, bChannel, 2);
                                        Core.insertChannel(bChannel, image, 0);
                                        Core.insertChannel(rChannel, image, 2);
                                        rChannel.release();
                                        bChannel.release();
                                        break;
                                    case Hls :
                                        Imgproc.cvtColor(image, image, ColorCode.Hls2Bgr.getCode());
                                        break;
                                    case HlsFull :
                                        Imgproc.cvtColor(image, image, ColorCode.Hls2BgrFull.getCode());
                                        break;
                                    case Hsv :
                                        Imgproc.cvtColor(image, image, ColorCode.Hsv2Bgr.getCode());
                                        break;
                                    case HsvFull :
                                        Imgproc.cvtColor(image, image, ColorCode.Hsv2BgrFull.getCode());
                                        break;
                                    default:
                                        break;
                                }
                            }
                            else {
                                Logger.error("Expecting image to be in the " + colorSpace +
                                        " color space but it has only one channel. " + 
                                        "Please send this log file to the developers!" );
                                Logger.error("The offending pipeline is:");
                                String pipelineStr;
                                try {
                                    pipelineStr = "\n" + editor.getPipeline().toXmlString();
                                }
                                catch (Exception ex) {
                                    pipelineStr = "Unavailable due to " + ex.toString();
                                }
                                Logger.error(pipelineStr);
                            }
                        }
                    }
                }
                model = result.model;
            }
        }

        if (model instanceof List) {
            String s = "";
            for (Object o : ((List) model)) {
                if (o != null) {
                    s += o.toString();
                }
                s += "\n";
            }
            modelTextPane.setText(s);
        }
        else {
            modelTextPane.setText(model == null ? "" : model.toString());
        }
        matView.setMat(image);
        if (image != null) {
            image.release();
        }
        resultStageNameLabel.setText(result == null || displayStage == null ? ""
                : (displayStage.getName() + " ( " + (result.processingTimeNs / 1000000.0)
                        + " ms / " + (editor.getPipeline().getTotalProcessingTimeNs() / 1000000.0) + " ms)"));

        if (selectedStage == null) {
            firstResultAction.setEnabled(false);
            previousResultAction.setEnabled(false);
            nextResultAction.setEnabled(false);
            lastResultAction.setEnabled(false);
        }
        else {
            int index = stages.indexOf(displayStage);
            firstResultAction.setEnabled(index > 0);
            previousResultAction.setEnabled(index > 0);
            nextResultAction.setEnabled(index < stages.size() - 1);
            lastResultAction.setEnabled(index < stages.size() - 1);
        }
    }
    
    /**
     * Determine if there is a model at the given point in the image and if one can be found,
     * return it. 
     * @param p
     * @return
     */
    private Object getModelAtPoint(Point p) {
        List<CvStage> stages = editor.getPipeline().getStages();

        CvStage displayStage = (pinnedStage != null) ? pinnedStage : selectedStage;

        Result result = null;
        Mat image = null;
        Object model = null;
        if (displayStage != null) {
            result = editor.getPipeline().getResult(displayStage);
            if (result != null) {
                image = result.image;
                model = result.model;
            }
        }

        if (model instanceof List) {
            for (Object o : (List) model) {
                if (isModelAtPoint(o, p)) {
                    return o;
                }
            }
        }
        else {
            if (isModelAtPoint(model, p)) {
                return model;
            }
        }
        return null;
    }
    
    private boolean isModelAtPoint(Object model, Point p) {
        if (model instanceof RotatedRect) {
            RotatedRect r = (RotatedRect) model;
            if (Math.abs(p.x - r.center.x) < 5 && Math.abs(p.y - r.center.y) < 5) {
                return true;
            }
        }
        else if (model instanceof KeyPoint) {
            KeyPoint kp = (KeyPoint) model;
            if (Math.abs(p.x - kp.pt.x) < 5 && Math.abs(p.y - kp.pt.y) < 5) {
                return true;
            }
        }
        else if (model instanceof Circle) {
            Circle c = (Circle) model;
            if (Math.abs(p.x - c.x) < 5 && Math.abs(p.y - c.y) < 5) {
                return true;
            }
        }
        return false;
    }

    public final Action firstResultAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.navigateFirst);
            putValue(NAME, "");
            putValue(SHORT_DESCRIPTION, "First pipeline stage.");
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            List<CvStage> stages = editor.getPipeline().getStages();
            CvStage newStage = stages.get(0);
            if (pinnedStage != null) {
                pinnedStage = newStage;
            }
            else {
                selectedStage = newStage;
            }
            updateAllEverything();
        }
    };

    public final Action previousResultAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.navigatePrevious);
            putValue(NAME, "");
            putValue(SHORT_DESCRIPTION, "Previous pipeline stage.");
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            List<CvStage> stages = editor.getPipeline().getStages();
            CvStage oldStage = (pinnedStage != null) ? pinnedStage : selectedStage;
            int index = stages.indexOf(oldStage);
            CvStage newStage = stages.get(index - 1);
            if (pinnedStage != null) {
                pinnedStage = newStage;
            }
            else {
                selectedStage = newStage;
            }
            updateAllEverything();
        }
    };

    public final Action nextResultAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.navigateNext);
            putValue(NAME, "");
            putValue(SHORT_DESCRIPTION, "Next pipeline stage.");
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            List<CvStage> stages = editor.getPipeline().getStages();
            CvStage oldStage = (pinnedStage != null) ? pinnedStage : selectedStage;
            int index = stages.indexOf(oldStage);
            CvStage newStage = stages.get(index + 1);
            if (pinnedStage != null) {
                pinnedStage = newStage;
            }
            else {
                selectedStage = newStage;
            }
            updateAllEverything();
        }
    };

    public final Action lastResultAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.navigateLast);
            putValue(NAME, "");
            putValue(SHORT_DESCRIPTION, "Last pipeline stage.");
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            List<CvStage> stages = editor.getPipeline().getStages();
            CvStage newStage = stages.get(stages.size() - 1);
            if (pinnedStage != null) {
                pinnedStage = newStage;
            }
            else {
                selectedStage = newStage;
            }
            updateAllEverything();
        }
    };

    public final Action pinResultAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.pinDisabled);
            putValue(NAME, "");
            putValue(SHORT_DESCRIPTION, "Pin pipeline stage output.");
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            if (pinnedStage == null) {
                pinnedStage = selectedStage;
                putValue(SMALL_ICON, Icons.pinEnabled);
            }
            else {
                pinnedStage = null;
                putValue(SMALL_ICON, Icons.pinDisabled);

                updateAllEverything();
            }
        }
    };

    public final Action colorResultAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.colorTrue);
            putValue(NAME, "");
            putValue(SHORT_DESCRIPTION, "Images displayed in true color.");
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            if (displayTrueColors) {
                displayTrueColors = false;
                putValue(SMALL_ICON, Icons.colorFalse);
                putValue(SHORT_DESCRIPTION, "Images displayed assuming BGR color space - colors may not look correct.");
            }
            else {
                displayTrueColors = true;
                putValue(SMALL_ICON, Icons.colorTrue);
                putValue(SHORT_DESCRIPTION, "Images displayed in true color.");
            }
            updateAllEverything();
        }
    };

    private JTextPane modelTextPane;
    private JLabel resultStageNameLabel;
    private MatView matView;
}
