package some.org;

public interface SomeInterface {
  void someMethod(long sleepTimeMS) throws Exception;

  class Impl1 implements SomeInterface {
    @Override
    public void someMethod(long sleepTimeMS) throws Exception {
      Thread.sleep(sleepTimeMS);
    }
  }

  class Impl2 implements SomeInterface {
    @Override
    public void someMethod(long sleepTimeMS) throws Exception {
      Thread.sleep(sleepTimeMS);
    }
  }
}
