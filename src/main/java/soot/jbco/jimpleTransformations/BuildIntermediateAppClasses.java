/* Soot - a J*va Optimization Framework
 * Copyright (C) 1997-1999 Raja Vallee-Rai
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the
 * Free Software Foundation, Inc., 59 Temple Place - Suite 330,
 * Boston, MA 02111-1307, USA.
 */

package soot.jbco.jimpleTransformations;

import java.util.Collection;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import soot.Body;
import soot.FastHierarchy;
import soot.G;
import soot.Hierarchy;
import soot.Local;
import soot.Modifier;
import soot.PatchingChain;
import soot.PrimType;
import soot.Scene;
import soot.SceneTransformer;
import soot.SootClass;
import soot.SootMethod;
import soot.SootMethodRef;
import soot.Type;
import soot.Unit;
import soot.Value;
import soot.ValueBox;
import soot.VoidType;
import soot.jbco.IJbcoTransform;
import soot.jbco.Main;
import soot.jbco.util.BodyBuilder;
import soot.jimple.IntConstant;
import soot.jimple.Jimple;
import soot.jimple.JimpleBody;
import soot.jimple.NullConstant;
import soot.jimple.SpecialInvokeExpr;
import soot.jimple.ThisRef;

/**
 * @author Michael Batchelder
 * 
 *         Created on 1-Feb-2006
 * 
 *         This class builds buffer classes between Application classes and
 *         their corresponding library superclasses. This allows for the hiding
 *         of all library method overrides to be hidden in a different class,
 *         thereby cloaking somewhat the mechanisms.
 */
public class BuildIntermediateAppClasses extends SceneTransformer implements IJbcoTransform {

	static int newclasses = 0;
	static int newmethods = 0;

	public void outputSummary() {
		out.println("New buffer classes created: " + newclasses);
		out.println("New buffer class methods created: " + newmethods);
	}

	public static String dependancies[] = new String[] { "wjtp.jbco_bapibm" };

	public String[] getDependancies() {
		return dependancies;
	}

	public static String name = "wjtp.jbco_bapibm";

	public String getName() {
		return name;
	}

	protected void internalTransform(String phaseName, Map<String, String> options) {
		if (output)
			out.println("Building Intermediate Classes...");

		soot.jbco.util.BodyBuilder.retrieveAllBodies();

		Scene scene = G.v().soot_Scene();
		// iterate through application classes, build intermediate classes
		Iterator<SootClass> it = scene.getApplicationClasses().snapshotIterator();
		while (it.hasNext()) {
			Vector<SootMethod> initMethodsToRewrite = new Vector<SootMethod>();
			Hashtable<String, SootMethod> methodsToAdd = new Hashtable<String, SootMethod>();
			SootClass c = it.next();
			SootClass cOrigSuperclass = c.getSuperclass();

			if (output)
				out.println("Processing " + c.getName() + " with super " + cOrigSuperclass.getName());

			Iterator<SootMethod> mIt = c.methodIterator();
			while (mIt.hasNext()) {
				SootMethod m = mIt.next();
				if (!m.isConcrete())
					continue;

				try {
					m.getActiveBody();
				} catch (Exception exc) {
					if (m.retrieveActiveBody() == null)
						throw new RuntimeException(m.getSignature() + " has no body. This was not expected dude.");
				}

				String subSig = m.getSubSignature();
				if (subSig.equals("void main(java.lang.String[])") && m.isPublic() && m.isStatic()) {
					continue; // skip the main method - it needs to be named
								// 'main'
				} else if (subSig.indexOf("init>(") > 0) {
					if (subSig.startsWith("void <init>(")) {
						initMethodsToRewrite.add(m);
					}
					continue; // skip constructors, just add for rewriting at
								// the end
				} else {
					scene.releaseActiveHierarchy();

					Hierarchy hierarchy = scene.getActiveHierarchy();
					Iterator<SootClass> cIt = hierarchy.getSuperclassesOfIncluding(cOrigSuperclass).iterator();
					while (cIt.hasNext()) {
						SootClass _c = cIt.next();
						if (_c.isLibraryClass() && _c.declaresMethod(subSig)
								&& hierarchy.isVisible(c, _c.getMethod(subSig))) {
							methodsToAdd.put(subSig, m);
							break;
						}
					}
				}
			}

			if (methodsToAdd.size() > 0) {
				String newName = ClassRenamer.getNewName("");
				ClassRenamer.oldToNewClassNames.put(newName, newName);
				String fullName = ClassRenamer.getNamePrefix(c.getName()) + newName;

				if (output)
					out.println("\tBuilding " + fullName);

				// make class but can't be final
				SootClass iC = new SootClass(fullName, c.getModifiers() & (Modifier.FINAL ^ 0xFFFF));
				Main.IntermediateAppClasses.add(iC);
				iC.setSuperclass(cOrigSuperclass);

				Scene.v().addClass(iC);
				iC.setApplicationClass();
				iC.setInScene(true);

				ThisRef thisRef = new ThisRef(iC.getType());

				Enumeration<String> keys = methodsToAdd.keys();
				while (keys.hasMoreElements()) {
					String sSig = keys.nextElement();
					SootMethod oldM = methodsToAdd.get(sSig);
					List<Type> paramTypes = oldM.getParameterTypes();
					Type rType = oldM.getReturnType();
					SootMethod newM;
					{ // build new junk method to call original method
						String newMName = MethodRenamer.getNewName();
						newM = Scene.v().makeSootMethod(newMName, paramTypes, rType, oldM.getModifiers(),
								oldM.getExceptions());
						iC.addMethod(newM);

						JimpleBody body = Jimple.v().newBody(newM);
						newM.setActiveBody(body);
						Collection<Local> locals = body.getLocals();
						PatchingChain<Unit> units = body.getUnits();

                        BodyBuilder.buildThisLocal(units, thisRef, locals);
						BodyBuilder.buildParameterLocals(units, locals, paramTypes);

						if (rType instanceof VoidType) {
							units.add(Jimple.v().newReturnVoidStmt());
						} else if (rType instanceof PrimType) {
                            units.add(Jimple.v().newReturnStmt(IntConstant.v(0)));
						} else {
							units.add(Jimple.v().newReturnStmt(NullConstant.v()));
						}
						newmethods++;
					} // end build new junk method to call original method

					{ // build copy of old method
						newM = Scene.v().makeSootMethod(oldM.getName(), paramTypes, rType, oldM.getModifiers(),
								oldM.getExceptions());
						iC.addMethod(newM);

						JimpleBody body = Jimple.v().newBody(newM);
						newM.setActiveBody(body);
						Collection<Local> locals = body.getLocals();
						PatchingChain<Unit> units = body.getUnits();

                        Local ths = BodyBuilder.buildThisLocal(units, thisRef, locals);
						List<Local> args = BodyBuilder.buildParameterLocals(units, locals, paramTypes);

						if (rType instanceof VoidType) {
							units.add(Jimple.v()
									.newInvokeStmt(Jimple.v().newVirtualInvokeExpr(ths, newM.makeRef(), args)));
							units.add(Jimple.v().newReturnVoidStmt());
						} else {
							Local loc = Jimple.v().newLocal("retValue", rType);
							body.getLocals().add(loc);

							units.add(Jimple.v().newAssignStmt(loc,
									Jimple.v().newVirtualInvokeExpr(ths, newM.makeRef(), args)));

							units.add(Jimple.v().newReturnStmt(loc));
						}
						newmethods++;
					} // end build copy of old method
				}
				c.setSuperclass(iC);

				// rewrite class init methods to call the proper superclass
				// inits
				int i = initMethodsToRewrite.size();
				while (i-- > 0) {
					SootMethod im = initMethodsToRewrite.remove(i);
					Body b = im.getActiveBody();
					Local thisLocal = b.getThisLocal();
					Iterator<Unit> uIt = b.getUnits().snapshotIterator();
					while (uIt.hasNext()) {
						Iterator<ValueBox> uUses = uIt.next().getUseBoxes().iterator();
						while (uUses.hasNext()) {
							Value v = uUses.next().getValue();
							if (v instanceof SpecialInvokeExpr) {
								SpecialInvokeExpr sie = (SpecialInvokeExpr) v;
								SootMethodRef smr = sie.getMethodRef();
								if (sie.getBase().equivTo(thisLocal)
										&& smr.declaringClass().getName().equals(cOrigSuperclass.getName())
										&& smr.getSubSignature().getString().startsWith("void <init>")) {
									SootMethod newSuperInit = null;
									if (!iC.declaresMethod("<init>", smr.parameterTypes())) {
										List<Type> paramTypes = smr.parameterTypes();
										newSuperInit = Scene.v().makeSootMethod("<init>", paramTypes, smr.returnType());
										iC.addMethod(newSuperInit);

										JimpleBody body = Jimple.v().newBody(newSuperInit);
										newSuperInit.setActiveBody(body);
										PatchingChain<Unit> initUnits = body.getUnits();
										Collection<Local> locals = body.getLocals();

                                        Local ths = BodyBuilder.buildThisLocal(initUnits, thisRef, locals);
										List<Local> args = BodyBuilder.buildParameterLocals(initUnits, locals,
												paramTypes);

										initUnits.add(Jimple.v()
												.newInvokeStmt(Jimple.v().newSpecialInvokeExpr(ths, smr, args)));
										initUnits.add(Jimple.v().newReturnVoidStmt());
									} else {
										newSuperInit = iC.getMethod("<init>", smr.parameterTypes());
									}

									sie.setMethodRef(newSuperInit.makeRef());
								}
							}
						}
					}
				} // end of rewrite class init methods to call the proper
					// superclass inits
			}
		}

		newclasses = Main.IntermediateAppClasses.size();

		scene.releaseActiveHierarchy();
		scene.getActiveHierarchy();
		scene.setFastHierarchy(new FastHierarchy());
	}
}