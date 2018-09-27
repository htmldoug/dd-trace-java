package some.org;

public class Helper {
  public void someMethod(long sleepTimeMS) {
    try {
      Thread.sleep(sleepTimeMS);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  public void n1(long sleepTimeMS, long n2TimeMS, long n3TimeMS, long n4TimeMS) {
    try {
      Thread.sleep(sleepTimeMS);
      n2(n2TimeMS, n3TimeMS, n4TimeMS);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  private void n2(long sleepTimeMS, long n3TimeMS, long n4TimeMS) {
    try {
      Thread.sleep(sleepTimeMS);
      n3(n3TimeMS, n4TimeMS);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  private void n3(long sleepTimeMS, long n4TimeMS) {
    try {
      Thread.sleep(sleepTimeMS);
      n4(n4TimeMS);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  private void n4(long sleepTimeMS) {
    try {
      Thread.sleep(sleepTimeMS);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  public void callByReflection() {
    try {
      Thread.sleep(11);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  public void exceptionEater() {
    try {
      exceptionThrower();
    } catch (Exception e) {
      // YUM!
    }
  }

  private void exceptionThrower() throws Exception {
    Thread.sleep(10);
    throw new RuntimeException("What did you think would happen?");
  }

  public void interfaceInvoker(SomeInterface someInterface) {
    try {
      someInterface.someMethod(10);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public void methodOnHelper(long sleepTimeMS) {
    try {
      Thread.sleep(sleepTimeMS);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public void methodOverridden(long sleepTimeMS) {
    try {
      Thread.sleep(sleepTimeMS);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static class HelperOverride extends Helper {
    @Override
    public void methodOverridden(long sleepTimeMS) {
      try {
        Thread.sleep(sleepTimeMS);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }
}
