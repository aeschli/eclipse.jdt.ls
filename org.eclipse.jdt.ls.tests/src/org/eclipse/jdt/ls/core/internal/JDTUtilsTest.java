/*******************************************************************************
 * Copyright (c) 2016-2017 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageDeclaration;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.ls.core.internal.managers.ProjectsManager;
import org.junit.Test;

import com.google.common.base.Throwables;

/**
 * @author Fred Bricon
 *
 */
public class JDTUtilsTest extends AbstractWorkspaceTest {

	@Test
	public void testGetPackageNameString() throws Exception {
		String content = "package foo.bar.internal";
		assertEquals("foo.bar.internal", JDTUtils.getPackageName(null, content));

		content = "some junk";
		assertEquals("", JDTUtils.getPackageName(null, content));

		content = "";
		assertEquals("", JDTUtils.getPackageName(null, content));

		assertEquals("", JDTUtils.getPackageName(null, (String)null));
	}

	@Test
	public void testGetPackageNameURI() throws Exception {
		URI src = Paths.get("projects", "eclipse", "hello", "src", "java", "Foo.java").toUri();
		String packageName = JDTUtils.getPackageName(null, src);
		assertEquals("java", packageName);
	}

	@Test
	public void testGetPackageNameNoSrc() throws Exception {
		URI uri = Paths.get("projects", "eclipse", "hello", "Foo.java").toUri();
		ICompilationUnit cu = JDTUtils.resolveCompilationUnit(uri.toString());
		String packageName = JDTUtils.getPackageName(cu.getJavaProject(), uri);
		assertEquals("", packageName);
	}

	@Test
	public void testGetPackageNameSrc() throws Exception {
		URI uri = Paths.get("projects", "eclipse", "hello", "src", "Foo.java").toUri();
		ICompilationUnit cu = JDTUtils.resolveCompilationUnit(uri.toString());
		String packageName = JDTUtils.getPackageName(cu.getJavaProject(), uri);
		assertEquals("", packageName);
	}

	@Test
	public void testResolveStandaloneCompilationUnit() throws CoreException {
		Path helloSrcRoot = Paths.get("projects", "eclipse", "hello", "src").toAbsolutePath();
		URI uri = helloSrcRoot.resolve(Paths.get("java", "Foo.java")).toUri();
		ICompilationUnit cu = JDTUtils.resolveCompilationUnit(uri.toString());
		assertNotNull("Could not find compilation unit for " + uri, cu);
		assertEquals(ProjectsManager.DEFAULT_PROJECT_NAME, cu.getResource().getProject().getName());
		IJavaElement[] elements = cu.getChildren();
		assertEquals(2, elements.length);
		assertTrue(IPackageDeclaration.class.isAssignableFrom(elements[0].getClass()));
		assertTrue(IType.class.isAssignableFrom(elements[1].getClass()));
		assertTrue(cu.getResource().isLinked());
		assertEquals(cu.getResource(), JDTUtils.findFile(uri));

		uri = helloSrcRoot.resolve("NoPackage.java").toUri();
		cu = JDTUtils.resolveCompilationUnit(uri.toString());
		assertNotNull("Could not find compilation unit for " + uri, cu);
		assertEquals(ProjectsManager.DEFAULT_PROJECT_NAME, cu.getResource().getProject().getName());
		elements = cu.getChildren();
		assertEquals(1, elements.length);
		assertTrue(IType.class.isAssignableFrom(elements[0].getClass()));
	}


	@Test
	public void testUnresolvableCompilationUnits() throws Exception {
		assertNull(JDTUtils.resolveCompilationUnit((String)null));
		assertNull(JDTUtils.resolveCompilationUnit((URI)null));
		assertNull(JDTUtils.resolveCompilationUnit(new URI("gopher://meh")));
		assertNull(JDTUtils.resolveCompilationUnit("foo/bar/Clazz.java"));
		assertNull(JDTUtils.resolveCompilationUnit("file:///foo/bar/Clazz.java"));
	}

	@Test
	public void testNonJavaCompilationUnit() throws Exception {
		Path root = Paths.get("projects", "maven", "salut").toAbsolutePath();
		URI uri = root.resolve("pom.xml").toUri();
		assertNull(JDTUtils.resolveCompilationUnit(uri));
		IProject project = WorkspaceHelper.getProject(ProjectsManager.DEFAULT_PROJECT_NAME);
		assertFalse(project.getFile("src/pom.xml").isLinked());
	}

	@Test
	public void testIgnoredUnknownSchemes() {
		PrintStream originalOut = System.out;
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			System.setOut(new PrintStream(baos, true));
			System.out.flush();
			JDTUtils.resolveCompilationUnit("inmemory:///foo/bar/Clazz.java");

			assertEquals("",baos.toString());

		} finally {
			System.setOut(originalOut);
		}
	}

	@Test
	public void testResolveStandaloneCompilationUnitWithinJob() throws Exception {
		WorkspaceJob job = new WorkspaceJob("Create link") {
			@Override
			public IStatus runInWorkspace(IProgressMonitor monitor) throws CoreException {
				try {
					testResolveStandaloneCompilationUnit();
				} catch (Exception e) {
					return StatusFactory.newErrorStatus("Failed to resolve CU", e);
				}
				return Status.OK_STATUS;
			}
		};
		job.setRule(WorkspaceHelper.getProject(ProjectsManager.DEFAULT_PROJECT_NAME));
		job.schedule();
		job.join(2_000, new NullProgressMonitor());
		assertNotNull(job.getResult());
		assertNull(getStackTrace(job.getResult()), job.getResult().getException());
		assertTrue(job.getResult().getMessage(), job.getResult().isOK());
	}

	private String getStackTrace(IStatus status) {
		if (status != null && status.getException() != null) {
			return Throwables.getStackTraceAsString(status.getException());
		}
		return null;
	}

	@Test
	public void testFakeCompilationUnit() throws Exception {
		String tempDir = System.getProperty("java.io.tmpdir");
		File dir = new File(tempDir, "/test/src/org/eclipse");
		dir.mkdirs();
		File file = new File(dir, "Test.java");
		file.createNewFile();
		URI uri = file.toURI();
		JDTUtils.resolveCompilationUnit(uri);
		IProject project = WorkspaceHelper.getProject(ProjectsManager.DEFAULT_PROJECT_NAME);
		IFile iFile = project.getFile("/src/org/eclipse/Test.java");
		assertTrue(iFile.getFullPath().toString() + " doesn't exist.", iFile.exists());
		Path path = Paths.get(tempDir + "/test");
		Files.walk(path, FileVisitOption.FOLLOW_LINKS).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
	}
}
