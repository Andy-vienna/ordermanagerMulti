package org.andy.om.multi;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Rectangle;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.Stream;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

public class Manager extends JFrame {

	private static final long serialVersionUID = 1L;
    private static final Logger logger = Logger.getLogger(Manager.class.getName());
    
    @SuppressWarnings("unused")
    private static ServerSocket lockSocket;

    private final JTree fileTree = new JTree();
    private String selectedBaseName = null;
    private JLabel actLabel = new JLabel("aktuell:  ");
    private JLabel lastCopiedLabel = new JLabel("kein Programm aktiv ...");
    private final JButton copyButton = new JButton("starten");
    private static Path sourceDir;
    private static Path targetDir;
    private static Path archiveDir;
    private Path selectedSourcePath = null;
    private static String lastProg = null;
    private WatchService watchService;
    private boolean watching = false;

	// ###################################################################################################################################################
	// ###################################################################################################################################################

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		SwingUtilities.invokeLater(Manager::new);
	}

	// ###################################################################################################################################################
	// ###################################################################################################################################################

	/**
	 * 
	 */
	public Manager() {
        super("Auswahl der Maschinenprogramme");

        if (isAlreadyRunning()) System.exit(0);
        try { setupLogger(); } catch (IOException e) { e.printStackTrace(); }

        loadConfig();

        try {
            Files.createDirectories(sourceDir);
            Files.createDirectories(targetDir);
            Files.createDirectories(archiveDir);
        } catch (IOException e) {
            logger.severe("fehler beim erzeugen der Pfade - " + e.getMessage());
        }

        setLayout(new BorderLayout());

        fileTree.setRootVisible(false);
        fileTree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        fileTree.setCellRenderer(new FolderOnlyTreeCellRenderer());

        fileTree.addTreeSelectionListener(e -> {
            Object selected = fileTree.getLastSelectedPathComponent();
            if (selected instanceof DefaultMutableTreeNode node && node.isLeaf()) {
                TreePath path = e.getPath();
                selectedBaseName = node.toString();
                selectedSourcePath = sourceDir.resolve(getRelativePath(path));
            } else {
                selectedBaseName = null;
                selectedSourcePath = null;
            }
        });
        
		JScrollPane treeScrollPane = new JScrollPane(fileTree);
		treeScrollPane.setBorder(new CompoundBorder(
		        new LineBorder(new Color(240, 240, 240), 3, true), // true = runde Ecken
		        new EmptyBorder(5, 5, 5, 5)
		));
		add(treeScrollPane, BorderLayout.CENTER);

		// Labels formattieren
		actLabel.setHorizontalAlignment(SwingConstants.LEFT);
		actLabel.setFont(new Font("Arial", Font.BOLD, 18));
		actLabel.setForeground(Color.BLACK);
		lastCopiedLabel.setHorizontalAlignment(SwingConstants.CENTER);
		lastCopiedLabel.setFont(new Font("Arial", Font.BOLD, 18));
		lastCopiedLabel.setForeground(Color.BLUE);

        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusRow.add(actLabel);
        statusRow.add(lastCopiedLabel);

        // Großer Button zentriert und formattiert
 		copyButton.setBorder(new RoundedBorder(10));
 		copyButton.setFont(new Font("Arial", Font.BOLD, 18));
 		copyButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
 		copyButton.setPreferredSize(new Dimension(0, 40));
 		copyButton.setAlignmentX(Component.CENTER_ALIGNMENT);

		// Alles in ein vertikales Panel
		JPanel actionPanel = new JPanel();
		actionPanel.setLayout(new BoxLayout(actionPanel, BoxLayout.Y_AXIS));
		actionPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5)); // schöner Rand
		actionPanel.add(statusRow);
		actionPanel.add(Box.createRigidArea(new Dimension(0, 5))); // Abstand
		actionPanel.add(copyButton);

		// und rein ins Hauptlayout
		add(actionPanel, BorderLayout.SOUTH);

		// updateFileList();
		updateFileTree();
		startWatching(); // WatchService starten

		copyButton.addActionListener(_ -> moveSelectedFiles());

		setSize(400, 300);
		setResizable(false);
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		setWindowIcon();
		setVisible(true);

		// Fenster auf der rechten Seite und volle Bildschirmhöhe
		positionWindow();
    }

	/**
	 *
	 */
	@Override
	public void dispose() {
		super.dispose();
		stopWatching(); // Sicherstellen, dass der WatchService beim Schließen gestoppt wird
	}

	// ###################################################################################################################################################
	// ###################################################################################################################################################

	/**
	 * 
	 */
	private void startWatching() {
		if (watching)
			return; // Schon aktiv

		watching = true;
		Thread watcherThread = new Thread(() -> {
			try {
				watchService = FileSystems.getDefault().newWatchService();
				sourceDir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
						StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);

				while (!Thread.currentThread().isInterrupted()) {
					WatchKey key = null;
					try {
						key = watchService.take(); // blockiert bis Event kommt
						if (key == null)
							continue;

						for (WatchEvent<?> _ : key.pollEvents()) {
							// Änderungen erkannt – Liste aktualisieren
						}

						SwingUtilities.invokeLater(this::updateFileTree);

					} catch (ClosedWatchServiceException | InterruptedException e) {
						break;
					} finally {
						if (key != null && !key.reset()) {
							break;
						}
					}
				}
			} catch (IOException e) {
				logger.severe("Fehler beim Starten des WatchService - " + e.getMessage());
			}
		});

		watcherThread.setDaemon(true);
		watcherThread.start();
	}

	/**
	 * 
	 */
	private void stopWatching() {
		if (watchService != null) {
			try {
				watchService.close(); // Beendet den WatchService
			} catch (IOException e) {
				logger.severe("Fehler beim Schließen des WatchService - " + e.getMessage());
			}
		}
		watching = false; // Setze das Flag zurück
	}

	// ###################################################################################################################################################
	// ###################################################################################################################################################

	/**
	 * @return
	 */
	private static boolean isAlreadyRunning() {
		try {
			lockSocket = new ServerSocket(54556);
			return false;
		} catch (IOException e) {
			logger.info("OrderManager ist bereits gestartet.");
			return true;
		}
	}

	/**
	 * @throws IOException
	 */
	private static void setupLogger() throws IOException {
		LogManager.getLogManager().reset();
		Logger rootLogger = Logger.getLogger("");
		ConsoleHandler consoleHandler = new ConsoleHandler();
		consoleHandler.setLevel(Level.INFO);
		rootLogger.addHandler(consoleHandler);

		FileHandler fileHandler = new FileHandler("ordermanager.log", true);
		fileHandler.setLevel(Level.ALL);
		fileHandler.setFormatter(new SimpleFormatter());
		rootLogger.addHandler(fileHandler);
	}

	/**
	 * 
	 */
	private void positionWindow() {
		int fixedWidth = 500;
		GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
		Rectangle screenBounds = ge.getMaximumWindowBounds(); // Arbeitsbereich ohne Taskleiste etc.

		int x = screenBounds.x + screenBounds.width - fixedWidth; // ganz rechts
		int y = screenBounds.y; // ganz oben
		int height = screenBounds.height;

		setBounds(x, y, fixedWidth, height);
	}

	/**
	 * 
	 */
	private void setWindowIcon() {
		try {
			// Icon aus dem Ressourcenordner laden (z. B. src/main/resources/icon.png)
			URL iconUrl = getClass().getResource("/icon.png"); // Pfad anpassen
			if (iconUrl != null) {
				Image icon = ImageIO.read(iconUrl);
				setIconImage(icon);
			} else {
				logger.info("Icon nicht gefunden.");
			}
		} catch (IOException e) {
			logger.severe("Fehler beim Laden des Icons: " + e.getMessage());
		}
	}

	/**
	 * 
	 */
	private void loadConfig() {
		Properties props = new Properties();
		try (FileInputStream fis = new FileInputStream("config.properties")) {
			props.load(fis);
			sourceDir = Paths.get(props.getProperty("sourceDir"));
			targetDir = Paths.get(props.getProperty("targetDir"));
			archiveDir = Paths.get(props.getProperty("archiveDir"));
			lastProg = props.getProperty("lastProg");
			
			if (!lastProg.isBlank()) {
			    String[] parts = lastProg.split("/");
			    if (parts.length == 2) {
			        String ordner = parts[0];
			        String name = parts[1];
			        lastCopiedLabel.setText("<html>" +
			            "<span style='color:#009900; font-weight:bold;'>" + ordner + "</span> / " +
			            "<span style='color:#0066cc;'>" + name + "</span></html>");
			    }
			}else {
				lastCopiedLabel.setText("kein Programm aktiv ...");
			}
		} catch (IOException e) {
			e.printStackTrace();
			JOptionPane.showMessageDialog(this, "Fehler beim Laden der Konfiguration.", "Fehler",
					JOptionPane.ERROR_MESSAGE);
			System.exit(1);
		}
	}

	/**
	 * @param lastP
	 * @throws IOException
	 */
	private static void saveConfig(String lastP) throws IOException {
		Properties props = new Properties();
		props.setProperty("sourceDir", sourceDir.toString());
		props.setProperty("targetDir", targetDir.toString());
		props.setProperty("archiveDir", archiveDir.toString());
		props.setProperty("lastProg", lastP);
		try (FileOutputStream out = new FileOutputStream("config.properties")) {
			props.store(out, "lastProg");
		}
	}

	/**
	 * @param dir
	 * @return
	 */
	private DefaultMutableTreeNode buildFileTree(Path dir) {
		DefaultMutableTreeNode root = new DefaultMutableTreeNode(dir.getFileName().toString());

		try (var paths = Files.list(dir)) {
			Map<String, List<Path>> groupedFiles = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

			for (Path path : paths.toList()) {
				if (Files.isDirectory(path)) {
					root.add(buildFileTree(path)); // rekursiv
				} else {
					String baseName = getBaseName(path.getFileName().toString());
					groupedFiles.computeIfAbsent(baseName, _ -> new ArrayList<>()).add(path);
				}
			}

			for (String baseName : groupedFiles.keySet()) {
				root.add(new DefaultMutableTreeNode(baseName));
			}

		} catch (IOException e) {
			logger.severe("Fehler beim Lesen von: " + dir + " - " + e.getMessage());
		}

		return root;
	}
	
	/**
	 * @param oldPath
	 * @param newModel
	 * @return
	 */
	private TreePath rebuildPath(TreePath oldPath, TreeModel newModel) {
	    Object[] oldNodes = oldPath.getPath();
	    List<Object> newNodes = new ArrayList<>();

	    TreeNode current = (TreeNode) newModel.getRoot();
	    newNodes.add(current);

	    for (int i = 1; i < oldNodes.length; i++) {
	        TreeNode next = null;
	        for (int j = 0; j < current.getChildCount(); j++) {
	            TreeNode child = current.getChildAt(j);
	            if (child.toString().equals(oldNodes[i].toString())) {
	                next = child;
	                break;
	            }
	        }
	        if (next == null) return null; // Knoten nicht mehr vorhanden
	        current = next;
	        newNodes.add(current);
	    }

	    return new TreePath(newNodes.toArray());
	}

	/**
	 * 
	 */
	private void updateFileTree() {
	    // Merke aktuell geöffnete Pfade
	    Set<TreePath> expandedPaths = new HashSet<>();
	    TreeModel model = fileTree.getModel();
	    if (model != null && model.getRoot() != null) {
	        Enumeration<TreePath> expandedEnum = fileTree.getExpandedDescendants(new TreePath(model.getRoot()));
	        if (expandedEnum != null) {
	            while (expandedEnum.hasMoreElements()) {
	                expandedPaths.add(expandedEnum.nextElement());
	            }
	        }
	    }

	    // Merke aktuell ausgewählten Pfad
	    TreePath selectedPath = fileTree.getSelectionPath();

	    // Neuen Baum aufbauen
	    DefaultMutableTreeNode root = buildFileTree(sourceDir);
	    DefaultTreeModel newModel = new DefaultTreeModel(root);
	    fileTree.setModel(newModel); // Modell setzen (führt zum "Zurücksetzen")

	    // Wiederherstellen der geöffneten Pfade
	    for (TreePath path : expandedPaths) {
	        TreePath rebuilt = rebuildPath(path, newModel);
	        if (rebuilt != null) {
	            fileTree.expandPath(rebuilt);
	        }
	    }

	    // Wiederherstellen der Auswahl
	    if (selectedPath != null) {
	        TreePath rebuilt = rebuildPath(selectedPath, newModel);
	        if (rebuilt != null) {
	            fileTree.setSelectionPath(rebuilt);
	        }
	    }
	}

	/**
	 * @param filename
	 * @return
	 */
	private String getBaseName(String filename) {
		int dotIndex = filename.lastIndexOf(".");
		String nameWithoutExtension = (dotIndex == -1) ? filename : filename.substring(0, dotIndex);

		// "_U" oder "_u" am Ende entfernen
		if (nameWithoutExtension.toLowerCase().endsWith("_u")) {
			nameWithoutExtension = nameWithoutExtension.substring(0, nameWithoutExtension.length() - 2);
		}

		return nameWithoutExtension;
	}
	
	private String getRelativePath(TreePath path) {
	    StringBuilder relativePath = new StringBuilder();
	    for (int i = 1; i < path.getPathCount(); i++) {
	        relativePath.append(path.getPathComponent(i).toString());
	        if (i < path.getPathCount() - 1)
	            relativePath.append("/");
	    }
	    return relativePath.toString();
	}

	// ###################################################################################################################################################
	// ###################################################################################################################################################

	/**
	 * 
	 */
	private void moveSelectedFiles() {
		
		if (selectedBaseName == null || selectedSourcePath == null) return;

		TreePath[] paths = fileTree.getSelectionPaths();

        if (paths == null || paths.length == 0) return;

        moveDstnFoldersToArchive();
        List<String> exts = new ArrayList<>();

        try {
            Files.walk(targetDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().equalsIgnoreCase("PROGRAM.MPF"))
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException ex) {
                            logger.severe("Fehler beim Löschen: " + ex.getMessage());
                        }
                    });

            for (TreePath path : paths) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                if (!node.isLeaf()) continue;

                String baseName = node.toString();
                Path srcPath = sourceDir.resolve(getRelativePath(path));

                List<Path> toArchive = new ArrayList<>();

                Files.list(srcPath.getParent())
                        .filter(Files::isRegularFile)
                        .filter(p -> getBaseName(p.getFileName().toString()).equals(baseName))
                        .forEach(p -> {
                            try {
                                Files.copy(p, targetDir.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                                toArchive.add(p);
                                if (p.getFileName().toString().toLowerCase().endsWith(".mpf")) {
                                    exts.add(p.getFileName().toString());
                                }
                            } catch (IOException e) {
                                logger.severe("Fehler beim Kopieren: " + e.getMessage());
                            }
                        });

                for (Path p : toArchive) {
                    try {
                        Path rel = sourceDir.relativize(p);
                        Path archiveTarget = archiveDir.resolve(rel);
                        Files.createDirectories(archiveTarget.getParent());
                        Files.move(p, archiveTarget, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        logger.severe("Fehler beim Archivieren: " + e.getMessage());
                    }
                }
            }

            deleteEmptyDirectories(sourceDir);

            StringBuilder content = new StringBuilder();
            for (String name : exts) {
                content.append("EXTCALL \"").append(name).append("\"\n");
            }
            content.append("M30\n");
            Files.writeString(targetDir.resolve("PROGRAM.MPF"), content.toString());

            lastCopiedLabel.setText("<html>" + paths.length + " Programm(e) geladen</html>");
            saveConfig("multi");

        } catch (IOException e) {
            logger.severe("Fehler beim Kopiervorgang: " + e.getMessage());
        }
    }
	
	/**
	 * 
	 */
	private void moveDstnFoldersToArchive() {
	    try (Stream<Path> paths = Files.walk(sourceDir)) {
	        paths
	            .filter(Files::isDirectory)
	            .filter(path -> path.getFileName().toString().equalsIgnoreCase("Dstn"))
	            .forEach(dsnSource -> {
	                try {
	                    Path relative = sourceDir.relativize(dsnSource);
	                    Path dsnTarget = archiveDir.resolve(relative);

	                    // Zielordner löschen, falls schon vorhanden
	                    if (Files.exists(dsnTarget)) {
	                        deleteDirectoryRecursively(dsnTarget);
	                    }

	                    // Zielstruktur vorbereiten
	                    Files.createDirectories(dsnTarget.getParent());

	                    // Dstn-Ordner verschieben
	                    Files.move(dsnSource, dsnTarget, StandardCopyOption.REPLACE_EXISTING);

	                    //logger.info("Dstn-Ordner verschoben:\nVon: " + dsnSource + "\nNach: " + dsnTarget);

	                } catch (IOException e) {
	                    logger.severe("Fehler beim Verschieben des Dstn-Ordners:\nVon: " + dsnSource + "\n" + e.getMessage());
	                }
	            });

	    } catch (IOException e) {
	        logger.severe("Fehler beim Durchsuchen nach Dstn-Ordnern: " + e.getMessage());
	    }
	}

	/**
	 * @param path
	 * @throws IOException
	 */
	private void deleteEmptyDirectories(Path dir) throws IOException {
		try (Stream<Path> paths = Files.walk(dir)) {
			paths.sorted(Comparator.reverseOrder()).filter(Files::isDirectory).filter(path -> !path.equals(dir)) // <--
																													// Wurzel
																													// NICHT
																													// löschen
					.forEach(path -> {
						try {
							if (Files.list(path).findAny().isEmpty()) {
								Files.delete(path);
							}
						} catch (IOException ignored) {
						}
					});
		}
	}
	
	/**
	 * @param dir
	 * @throws IOException
	 */
	private void deleteDirectoryRecursively(Path dir) throws IOException {
	    if (!Files.exists(dir)) return;

	    try (Stream<Path> walk = Files.walk(dir)) {
	        walk.sorted(Comparator.reverseOrder())
	            .forEach(path -> {
	                try {
	                    Files.delete(path);
	                } catch (IOException ignored) {}
	            });
	    }
	}

}
