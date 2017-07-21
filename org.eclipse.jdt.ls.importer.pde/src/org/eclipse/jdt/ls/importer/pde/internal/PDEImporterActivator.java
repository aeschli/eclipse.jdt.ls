/*******************************************************************************
 * Copyright (c) 2017 Microsoft Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Microsoft Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.jdt.ls.importer.pde.internal;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collection;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.core.ProvisionException;
import org.eclipse.equinox.p2.engine.IProfile;
import org.eclipse.equinox.p2.engine.IProfileRegistry;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.operations.InstallOperation;
import org.eclipse.equinox.p2.operations.ProvisioningSession;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.p2.repository.IRepositoryManager;
import org.eclipse.equinox.p2.repository.artifact.IArtifactRepositoryManager;
import org.eclipse.equinox.p2.repository.artifact.IFileArtifactRepository;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepository;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.ServiceReference;

public class PDEImporterActivator implements BundleActivator {

	public static final String PLUGIN_ID = "org.eclipse.jdt.ls.importer.pde";

	private static BundleContext context;

	static BundleContext getContext() {
		return context;
	}

	@Override
	public void start(BundleContext bundleContext) throws Exception {
		PDEImporterActivator.context = bundleContext;
		testBundles();
	}

	private void testBundles() {
		Bundle[] bundles = context.getBundles();
		String all = "";
		String hello = "";
		for (Bundle bundle : bundles) {
			String id = bundle.getSymbolicName();
			all += '\n' + id;
			if (id.startsWith("Hello") || id.startsWith("hello")) {
				hello += '\n' + id;
			}
		}
		System.out.println(all);
		System.out.println("Hello" + hello);
	}

	/**
	 *
	 */
	private void addBundles() {
		String path = "file:/home/aeschli/workspaces/eclipseworkspace/mysite";

		IProvisioningAgent agent = acquireService(IProvisioningAgent.class);
		try {
			//get the repository managers and define our repositories
			IMetadataRepositoryManager manager = (IMetadataRepositoryManager) agent.getService(IMetadataRepositoryManager.SERVICE_NAME);
			IArtifactRepositoryManager artifactManager = (IArtifactRepositoryManager) agent.getService(IArtifactRepositoryManager.SERVICE_NAME);
			manager.addRepository(new URI(path));
			artifactManager.addRepository(new URI(path));

			//Load and query the metadata
			IMetadataRepository metadataRepo = manager.loadRepository(new URI(path), new NullProgressMonitor());
			Collection<IInstallableUnit> toInstall = metadataRepo.query(QueryUtil.createIUQuery("HelloPlugin"), new NullProgressMonitor()).toUnmodifiableSet();

			//Creating an operation
			InstallOperation installOperation = new InstallOperation(new ProvisioningSession(agent), toInstall);
			if (installOperation.resolveModal(new NullProgressMonitor()).isOK()) {
				Job job = installOperation.getProvisioningJob(new NullProgressMonitor());
				job.addJobChangeListener(new JobChangeAdapter() {
					@Override
					public void done(IJobChangeEvent event) {
						IStatus result = event.getResult();
						System.out.println(result.getMessage());
					}
				});
				job.schedule();
				job.join();
				for (URI repositoryUri : artifactManager.getKnownRepositories(IRepositoryManager.REPOSITORIES_SYSTEM)) {
					System.out.println(repositoryUri);
				}
				IProfileRegistry profileRegistry = (IProfileRegistry) agent.getService(IProfileRegistry.SERVICE_NAME);
				IProfile profile = profileRegistry.getProfile(IProfileRegistry.SELF);
				Collection<IInstallableUnit> alreadyInstalled = profile.available(QueryUtil.ALL_UNITS, null).toUnmodifiableSet();
				for (IInstallableUnit i : alreadyInstalled) {
					System.out.println(i.getId());
				}

				try {
					URL bundleLocationURL = new URL("file:/home/aeschli/workspaces/eclipseworkspace/mysite/plugins/HelloPlugin_1.0.0.201707191155.jar");

					Bundle bundle = context.installBundle(bundleLocationURL.toExternalForm());
					System.out.println(bundle);
				}
				catch (BundleException e) {
					System.out.println(e);
				} catch (MalformedURLException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}

				if (artifactManager instanceof IFileArtifactRepository) {
					//IFileArtifactRepository repository = (IFileArtifactRepository) manager.loadRepository(bundlePool, monitor);
				}
				Bundle[] bundles = context.getBundles();
				String all = "";
				for (Bundle bundle : bundles) {
					all += '\n' + bundle.getSymbolicName();
				}
				System.out.println(all);
			}
		} catch (URISyntaxException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ProvisionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (OperationCanceledException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {

		}
	}

	@Override
	public void stop(BundleContext bundleContext) throws Exception {
		PDEImporterActivator.context = null;
	}

	@SuppressWarnings("unchecked")
	public static <T> T acquireService(Class<T> serviceInterface) {
		ServiceReference<T> reference = (ServiceReference<T>) context.getServiceReference(serviceInterface.getName());
		if (reference == null) {
			return null;
		}
		T service = context.getService(reference);
		if (service != null) {
			context.ungetService(reference);
		}
		return service;
	}

	public static void log(IStatus status) {
		if (context != null) {
			Platform.getLog(context.getBundle()).log(status);
		}
	}

	public static void log(CoreException e) {
		log(e.getStatus());
	}

	public static void logError(String message) {
		if (context != null) {
			log(new Status(IStatus.ERROR, context.getBundle().getSymbolicName(), message));
		}
	}

	public static void logInfo(String message) {
		if (context != null) {
			log(new Status(IStatus.INFO, context.getBundle().getSymbolicName(), message));
		}
	}

	public static void logException(String message, Throwable ex) {
		if (context != null) {
			log(new Status(IStatus.ERROR, context.getBundle().getSymbolicName(), message, ex));
		}
	}
}
