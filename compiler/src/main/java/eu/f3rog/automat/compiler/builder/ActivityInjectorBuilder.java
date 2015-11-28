package eu.f3rog.automat.compiler.builder;

import android.os.Bundle;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;

import java.util.ArrayList;
import java.util.List;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;

import eu.f3rog.automat.compiler.name.GCN;
import eu.f3rog.automat.compiler.util.ProcessorError;

/**
 * Class {@link ActivityInjectorBuilder}
 *
 * @author FrantisekGazo
 * @version 2015-11-27
 */
public class ActivityInjectorBuilder extends BaseInjectorBuilder {

    private List<VariableElement> mExtras = new ArrayList<>();

    public ActivityInjectorBuilder(ClassName arg) throws ProcessorError {
        super(GCN.CLASS_INJECTOR, arg);
    }

    public void addExtra(VariableElement e) {
        mExtras.add(e);
    }

    public List<VariableElement> getExtras() {
        return mExtras;
    }

    @Override
    public void addInjectMethod() {
        String target = "target";
        MethodSpec.Builder method = MethodSpec.methodBuilder(METHOD_NAME_INJECT)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(getArgClassName(), target);

        method.beginControlFlow("if ($N.getIntent() == null || $N.getIntent().getExtras() == null)", target, target)
                .addStatement("return")
                .endControlFlow();

        String extras = "extras";
        method.addStatement("$T $N = $N.getIntent().getExtras()", Bundle.class, extras, target);

        for (int i = 0; i < mExtras.size(); i++) {
            VariableElement extra = mExtras.get(i);
            TypeName type = ClassName.get(extra.asType());
            method.addStatement("$N.$N = ($T) $N.$N($S)",
                    target, extra.getSimpleName(), type,
                    extras, getExtraGetterName(type), getExtraId(extra));
        }

        getBuilder().addMethod(method.build());
    }
}