package net.md_5.simulare;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Mojo which converts Java 7 classes back to Java 6.
 */
@Mojo(name = "transform", defaultPhase = LifecyclePhase.PROCESS_CLASSES)
public class TransformMojo extends AbstractMojo
{

    /**
     * Directory of classes to process.
     */
    @Parameter(property = "project.build.outputDirectory", required = true)
    private File classDirectory;

    @Override
    public void execute() throws MojoExecutionException
    {
        walk( classDirectory, new SuffixFilter( ".class" ) );
    }

    private void walk(File dir, FileFilter filter) throws MojoExecutionException
    {
        for ( File file : dir.listFiles( filter ) )
        {
            if ( file.isDirectory() )
            {
                walk( file, filter );
            } else
            {
                process( file );
            }
        }
    }

    private void process(File file) throws MojoExecutionException
    {
        try
        {
            FileInputStream is = new FileInputStream( file );

            ClassReader reader;
            try
            {
                reader = new ClassReader( is );
            } finally
            {
                is.close();
            }

            ClassWriter writer = new ClassWriter( reader, 0 );
            ClassTransformer transformer = new ClassTransformer( writer );
            reader.accept( transformer, ClassReader.EXPAND_FRAMES );

            FileOutputStream out = new FileOutputStream( file );
            try
            {
                out.write( writer.toByteArray() );
            } finally
            {
                out.close();
            }
        } catch ( IOException ex )
        {
            throw new MojoExecutionException( "Error whilst reading / writing class file : " + file, ex );
        }

        getLog().info( "Transformed: " + file );
    }

    private static class ClassTransformer extends ClassVisitor
    {

        public ClassTransformer(ClassVisitor cv)
        {
            super( Opcodes.ASM5, cv );
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces)
        {
            if ( version > Opcodes.V1_7 )
            {
                throw new RuntimeException( "Cannot transform classes greater than Java 1.7" );
            }

            super.visit( ( version == Opcodes.V1_7 ) ? Opcodes.V1_6 : version, access, name, signature, superName, interfaces );
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions)
        {
            return new MethodTransformer( super.visitMethod( access, name, desc, signature, exceptions ) );
        }

        private static class MethodTransformer extends MethodVisitor
        {

            public MethodTransformer(MethodVisitor mv)
            {
                super( Opcodes.ASM5, mv );
            }

            @Override
            public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs)
            {
                throw new RuntimeException( "InvokeDynamic instructions not supported" );
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf)
            {
                if ( owner.equals( "java/lang/Throwable" ) && name.equals( "addSuppressed" ) )
                {
                    // Start try block
                    Label start = new Label();
                    // End try block
                    Label end = new Label();
                    // Catch block start
                    Label handler = new Label();

                    // We could rewrite the calls, but this is better as if we are running on Java 7+, we will still get supressed exceptions
                    super.visitTryCatchBlock( start, end, handler, "java/lang/NoSuchMethodError" );
                    // try {
                    super.visitLabel( start );
                    // addSuppressed(...)
                    super.visitMethodInsn( opcode, owner, name, desc, itf );
                    // }
                    super.visitLabel( end );

                    // This is a pointer to the next insn, ie: previous execution flow
                    Label next = new Label();
                    // If we survived (Java 7+) we can just continue onwards
                    super.visitJumpInsn( Opcodes.GOTO, next );

                    // Start the actual catch block
                    super.visitLabel( handler );
                    // Pop the NoSuchMethodError off the stack, then continue on our merry way
                    super.visitInsn( Opcodes.POP );

                    // Leave a marker for the next instruction
                    super.visitLabel( next );
                } else
                {
                    super.visitMethodInsn( opcode, owner, name, desc, itf );
                }
            }
        }
    }

    private static class SuffixFilter implements FileFilter
    {

        private final String suffix;

        public SuffixFilter(String suffix)
        {
            this.suffix = suffix;
        }

        public boolean accept(File pathname)
        {
            return pathname.isDirectory() || pathname.getName().endsWith( suffix );
        }
    }
}
