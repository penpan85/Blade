package eu.f3rog.blade.weaving;

import com.sun.tools.javac.util.Pair;

import eu.f3rog.afterburner.exception.AfterBurnerImpossibleException;
import eu.f3rog.afterburner.inserts.InsertableConstructor;
import eu.f3rog.blade.compiler.builder.annotation.WeaveBuilder;
import eu.f3rog.blade.core.Weave;
import eu.f3rog.blade.core.Weaves;
import eu.f3rog.blade.weaving.util.AWeaver;
import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.NotFoundException;
import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.annotation.Annotation;
import javassist.bytecode.annotation.AnnotationMemberValue;
import javassist.bytecode.annotation.ArrayMemberValue;
import javassist.bytecode.annotation.MemberValue;

import static eu.f3rog.blade.weaving.util.WeavingUtil.getAnnotations;

public class BladeWeaver extends AWeaver {

    private static class Metadata {
        String into;
        CtClass[] args;
        String statement;
    }

    private static final String HELPER_NAME_FORMAT = "%s.%s_Helper";

    /**
     * Constructor
     */
    public BladeWeaver(boolean debug) {
        super(debug);
    }

    @Override
    public void weave(CtClass helperClass, CtClass intoClass) {
        lognl("Weaving starts (%s)", intoClass.getSimpleName());
        try {
            ClassPool classPool = intoClass.getClassPool();

            // weave field metadata
            for (CtField field : helperClass.getDeclaredFields()) {
                lognl("field '%s'", field.getName());

                Metadata[] metadata = loadWeaveMetadata(classPool, field);
                weave(metadata, intoClass, field);
            }

            // weave method metadata
            for (CtMethod method : helperClass.getDeclaredMethods()) {
                lognl("method '%s'", method.getName());

                Metadata[] metadata = loadWeaveMetadata(classPool, method);
                weave(metadata, intoClass, null);
            }

            // weave interfaces
            for (CtClass interfaceClass : helperClass.getInterfaces()) {
                lognl("interface '%s'", interfaceClass.getName());

                Interfaces.weaveInterface(interfaceClass, intoClass, getAfterBurner());
            }

            lognl("Weaving done (%s)", intoClass.getSimpleName());
        } catch (Exception e) {
            lognl("");
            lognl("Weaving failed! (%s)", intoClass.getSimpleName());
            lognl("");
            e.printStackTrace();
            throw new IllegalStateException(e);
        }
    }

    private void weave(Metadata[] m, CtClass intoClass, CtField helperField) throws NotFoundException, CannotCompileException, AfterBurnerImpossibleException {
        for (int i = 0; i < m.length; i++) {
            Metadata metadata = m[i];

            if (metadata == null) {
                lognl(" ~x nowhere");
                continue;
            }

            if (helperField != null && Weave.WEAVE_FIELD.equals(metadata.into)) {
                // weave field
                CtField f = new CtField(helperField.getType(), helperField.getName(), intoClass);
                f.setModifiers(helperField.getModifiers());

                log(" ~> field '%s'", f.getName());
                if (metadata.statement != null) {
                    lognl(" ~~~ %s", metadata.statement);
                    intoClass.addField(f, CtField.Initializer.byExpr(metadata.statement));
                } else {
                    lognl(" ~~~ without statement");
                    intoClass.addField(f);
                }
            } else {
                String body = "{ " + metadata.statement + " }";

                if (Weave.WEAVE_CONSTRUCTOR.equals(metadata.into)) {
                    log(" ~> constructor");
                    // weave into constructor
                    getAfterBurner().insertConstructor(new SpecificConstructor(body, intoClass, metadata.args));
                    lognl(" ~~~ %s", body);
                } else {
                    // weave into method
                    Pair<WeaveBuilder.MethodWeaveType, String> methodInfo = WeaveBuilder.parseMethodName(metadata.into);
                    log(" ~> method '%s' %s", methodInfo.snd, methodInfo.fst);
                    switch (methodInfo.fst) {
                        case AT_BEGINNIG:
                            getAfterBurner().atBeginningOfOverrideMethod(body, intoClass, methodInfo.snd, metadata.args);
                            break;
                        case BEFORE_SUPER:
                            try {
                                getAfterBurner().beforeOverrideMethod(body, intoClass, methodInfo.snd, metadata.args);
                            } catch (Exception e) { // put at beginning if super not found
                                getAfterBurner().atBeginningOfOverrideMethod(body, intoClass, methodInfo.snd, metadata.args);
                            }
                            break;
                        case AFTER_SUPER:
                            try {
                                getAfterBurner().afterOverrideMethod(body, intoClass, methodInfo.snd, metadata.args);
                            } catch (Exception e) { // put at beginning if super not found
                                getAfterBurner().atBeginningOfOverrideMethod(body, intoClass, methodInfo.snd, metadata.args);
                            }
                            break;
                        default:
                            throw new IllegalStateException();
                    }
                    lognl(" ~~~ %s", body);
                }
            }
        }
    }

    private CtClass getHelper(CtClass cls) throws NotFoundException {
        return cls.getClassPool()
                .get(String.format(HELPER_NAME_FORMAT, cls.getPackageName(), cls.getSimpleName()));
    }

    private boolean hasHelper(CtClass cls) {
        try {
            return getHelper(cls) != null;
        } catch (NotFoundException e) {
            return false;
        }
    }

    private Metadata[] loadWeaveMetadata(ClassPool classPool, CtMethod method) throws NotFoundException {
        AnnotationsAttribute attr = getAnnotations(method);
        if (attr != null) {
            return loadWeaveMetadata(classPool, attr);
        }

        return new Metadata[0];
    }

    private Metadata[] loadWeaveMetadata(ClassPool classPool, CtField field) throws NotFoundException {
        AnnotationsAttribute attr = getAnnotations(field);
        if (attr != null) {
            return loadWeaveMetadata(classPool, attr);
        }

        return new Metadata[0];
    }

    private Metadata[] loadWeaveMetadata(ClassPool classPool, AnnotationsAttribute attr) throws NotFoundException {
        Annotation a;

        a = attr.getAnnotation(Weave.class.getName());
        if (a != null) {
            return new Metadata[]{readWeaveAnnotation(a, classPool)};
        }

        a = attr.getAnnotation(Weaves.class.getName());
        if (a != null) {
            return readWeavesAnnotation(a, classPool);
        }

        return new Metadata[0];
    }


    private Metadata readWeaveAnnotation(Annotation weaveAnnotation, ClassPool classPool) throws NotFoundException {
        Metadata metadata = new Metadata();

        // get INTO
        MemberValue val = weaveAnnotation.getMemberValue("into");
        if (val != null) {
            metadata.into = val.toString().replaceAll("\"", "");
        }
        // get INTO ARGS
        metadata.args = readArgs(weaveAnnotation, classPool);
        // get STATEMENT
        val = weaveAnnotation.getMemberValue("statement");
        if (val != null) {
            metadata.statement = val.toString().replaceAll("\"", "");
            metadata.statement = metadata.statement.replaceAll("'", "\"");
            if (metadata.statement.length() == 0) {
                metadata.statement = null;
            }
        }

        return metadata;
    }

    private CtClass[] readArgs(Annotation weaveAnnotation, ClassPool classPool) throws NotFoundException {
        ArrayMemberValue arrayMemberValue = (ArrayMemberValue) weaveAnnotation.getMemberValue("args");
        if (arrayMemberValue != null) {
            MemberValue[] memberValues = arrayMemberValue.getValue();
            CtClass[] classes = new CtClass[memberValues.length];
            for (int i = 0; i < memberValues.length; i++) {
                String className = memberValues[i].toString().replaceAll("\"", "");
                classes[i] = classPool.get(className);
            }
            return classes;
        } else {
            return new CtClass[0];
        }
    }

    private Metadata[] readWeavesAnnotation(Annotation weavesAnnotation, ClassPool classPool) throws NotFoundException {
        ArrayMemberValue arrayMemberValue = (ArrayMemberValue) weavesAnnotation.getMemberValue("value");
        if (arrayMemberValue != null) {
            MemberValue[] memberValues = arrayMemberValue.getValue();
            Metadata[] annotations = new Metadata[memberValues.length];
            for (int i = 0; i < memberValues.length; i++) {
                AnnotationMemberValue memberValue = (AnnotationMemberValue) memberValues[i];
                annotations[i] = readWeaveAnnotation(memberValue.getValue(), classPool);
            }
            return annotations;
        } else {
            return new Metadata[0];
        }
    }

    private final class SpecificConstructor extends InsertableConstructor {

        private final CtClass[] mRequiredParams;
        private final String mBody;

        public SpecificConstructor(String body, CtClass classToInsertInto, CtClass... params) {
            super(classToInsertInto);
            mRequiredParams = params;
            mBody = body;
        }

        @Override
        public String getConstructorBody(CtClass[] paramClasses) throws AfterBurnerImpossibleException {
            return mBody;
        }

        @Override
        public boolean acceptParameters(CtClass[] paramClasses) throws AfterBurnerImpossibleException {
            if (paramClasses.length != mRequiredParams.length) {
                return false;
            }
            for (int i = 0, c = mRequiredParams.length; i < c; i++) {
                if (!mRequiredParams[i].equals(paramClasses[i])) {
                    return false;
                }
            }
            return true;
        }
    }

}
