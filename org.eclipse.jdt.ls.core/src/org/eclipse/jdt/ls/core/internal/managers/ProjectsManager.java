/*******************************************************************************
 * Copyright (c) 2016-2017 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *     Microsoft Corporation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.managers;

import static java.util.Arrays.asList;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionPoint;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.ls.core.IProjectImporter;
import org.eclipse.jdt.ls.core.internal.ActionableNotification;
import org.eclipse.jdt.ls.core.internal.JDTUtils;
import org.eclipse.jdt.ls.core.internal.JavaClientConnection.JavaLanguageClient;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.ServiceStatus;
import org.eclipse.jdt.ls.core.internal.StatusFactory;
import org.eclipse.jdt.ls.core.internal.preferences.PreferenceManager;
import org.eclipse.jdt.ls.core.internal.preferences.Preferences.FeatureStatus;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.TextDocumentIdentifier;


public class ProjectsManager {

	public static final String DEFAULT_PROJECT_NAME= "jdt.ls-java-project";
	private PreferenceManager preferenceManager;
	private JavaLanguageClient client;
	private IBuildSupport activeBuildSupport;

	public enum CHANGE_TYPE { CREATED, CHANGED, DELETED};

	public ProjectsManager(PreferenceManager preferenceManager) {
		this.preferenceManager = preferenceManager;

	}

	public void initializeProjects(final String projectPath, IProgressMonitor monitor) throws CoreException, OperationCanceledException {
		this.activeBuildSupport = null;

		// Run as a Java runnable to trigger any build while importing
		JavaCore.run(new IWorkspaceRunnable() {
			@Override
			public void run(IProgressMonitor monitor) throws CoreException {
				SubMonitor subMonitor = SubMonitor.convert(monitor, 100);
				createJavaProject(getDefaultProject(), subMonitor.split(10));
				if (projectPath != null) {
					File userProjectRoot = new File(projectPath);
					IProjectImporter importer = getImporter(userProjectRoot, subMonitor.split(20));
					if (importer != null) {
						importer.importToWorkspace(subMonitor.split(70));
					}
				}
				subMonitor.done();
			}
		}, monitor);
	}

	private static IWorkspaceRoot getWorkspaceRoot() {
		return ResourcesPlugin.getWorkspace().getRoot();
	}

	public void fileChanged(String uriString, CHANGE_TYPE changeType) {
		if (uriString == null) {
			return;
		}
		IResource resource = JDTUtils.findFile(uriString);
		if (resource == null) {
			return;
		}
		try {
			if (changeType == CHANGE_TYPE.DELETED) {
				resource = resource.getParent();
			}
			if (resource != null) {
				resource.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
			}
			if (isBuildFile(resource)) {
				FeatureStatus status = preferenceManager.getPreferences().getUpdateBuildConfigurationStatus();
				switch (status) {
				case automatic:
						updateProject(resource.getProject());
					break;
				case disabled:
					break;
				default:
					if (client != null) {
						String cmd = "java.projectConfiguration.status";
						TextDocumentIdentifier uri = new TextDocumentIdentifier(uriString);
						ActionableNotification updateProjectConfigurationNotification = new ActionableNotification()
								.withSeverity(MessageType.Info)
								.withMessage("A build file was modified. Do you want to synchronize the Java classpath/configuration?")
								.withCommands(asList(
										new Command("Never", cmd, asList(uri,FeatureStatus.disabled)),
										new Command("Now", cmd, asList(uri, FeatureStatus.interactive)),
										new Command("Always", cmd, asList(uri, FeatureStatus.automatic))
										));
						client.sendActionableNotification(updateProjectConfigurationNotification);
					}
				}
			}
		} catch (CoreException e) {
			JavaLanguageServerPlugin.logException("Problem refreshing workspace", e);
		}
	}

	public boolean isBuildFile(IResource resource) {
		return this.activeBuildSupport != null && this.activeBuildSupport.isBuildFile(resource);
	}

	private IProjectImporter getImporter(File rootFolder, IProgressMonitor monitor) throws OperationCanceledException, CoreException {
		Collection<IProjectImporter> importers = importers();
		SubMonitor subMonitor = SubMonitor.convert(monitor, importers.size());
		IProjectImporter preferred = null;
		int preferredRelevance = -1;
		for (IProjectImporter importer : importers) {
			importer.initialize(rootFolder);
			int relevance = importer.applies(subMonitor.split(1));
			if (relevance > preferredRelevance) {
				preferred = importer;
				preferredRelevance = relevance;
			}
		}
		return preferred;
	}

	public IProject getDefaultProject() {
		return getWorkspaceRoot().getProject(DEFAULT_PROJECT_NAME);
	}

	private Collection<IProjectImporter> importers() {
		Collection<IProjectImporter> importers = new ArrayList<>();
		IExtensionPoint extensionPoint = Platform.getExtensionRegistry().getExtensionPoint(JavaLanguageServerPlugin.PLUGIN_ID, "importers");
		IConfigurationElement[] configs = extensionPoint.getConfigurationElements();
		for (int i = 0; i < configs.length; i++) {
			try {
				importers.add((IProjectImporter) configs[i].createExecutableExtension("class")); //$NON-NLS-1$
			} catch (CoreException e) {
				JavaLanguageServerPlugin.log(e.getStatus());
			}
		}
		return importers;
	}

	public IProject createJavaProject(IProject project, IProgressMonitor monitor) throws CoreException, OperationCanceledException {
		if (project.exists()) {
			return project;
		}
		JavaLanguageServerPlugin.logInfo("Creating the default Java project");
		//Create project
		project.create(monitor);
		project.open(monitor);

		//Turn into Java project
		IProjectDescription description = project.getDescription();
		description.setNatureIds(new String[] { JavaCore.NATURE_ID });
		project.setDescription(description, monitor);
		IJavaProject javaProject = JavaCore.create(project);

		//Add build output folder
		IFolder output = project.getFolder("bin");
		if (!output.exists()) {
			output.create(true, true, monitor);
		}
		javaProject.setOutputLocation(output.getFullPath(), monitor);

		//Add source folder
		IFolder source = project.getFolder("src");
		if (!source.exists()) {
			source.create(true, true, monitor);
		}
		IPackageFragmentRoot root = javaProject.getPackageFragmentRoot(source);
		IClasspathEntry src =JavaCore.newSourceEntry(root.getPath());

		//Find default JVM
		IClasspathEntry jre = JavaRuntime.getDefaultJREContainerEntry();

		//Add JVM to project class path
		javaProject.setRawClasspath(new IClasspathEntry[]{jre, src} , monitor);

		JavaLanguageServerPlugin.logInfo("Finished creating the default Java project");
		return project;
	}

	public void updateProject(IProject project) {
		if (activeBuildSupport == null || !activeBuildSupport.applies(project)) {
			return;
		}

		JavaLanguageServerPlugin.sendStatus(ServiceStatus.Message, "Updating "+ project.getName() + " configuration");
		WorkspaceJob job = new WorkspaceJob("Update project "+project.getName()) {
			@Override
			public IStatus runInWorkspace(IProgressMonitor monitor) {
				IStatus status = Status.OK_STATUS;
				String projectName = project.getName();
				try {
					long start = System.currentTimeMillis();
					activeBuildSupport.update(project, monitor);
					long elapsed = System.currentTimeMillis() - start;
					JavaLanguageServerPlugin.logInfo("Updated "+projectName + " in "+ elapsed +" ms");
				} catch (CoreException e) {
					String msg = "Error updating "+projectName;
					JavaLanguageServerPlugin.logError(msg);
					status = StatusFactory.newErrorStatus(msg, e);
				}
				return status;
			}
		};
		job.schedule();
	}

	public void setConnection(JavaLanguageClient client) {
		this.client = client;
	}
}
