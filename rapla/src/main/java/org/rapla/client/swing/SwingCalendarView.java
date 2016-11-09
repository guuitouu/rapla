
/*--------------------------------------------------------------------------*
 | Copyright (C) 2006  Christopher Kohlhaas                                 |
 |                                                                          |
 | This program is free software; you can redistribute it and/or modify     |
 | it under the terms of the GNU General Public License as published by the |
 | Free Software Foundation. A copy of the license has been included with   |
 | these distribution in the COPYING file, if not go to www.fsf.org         |
 |                                                                          |
 | As a special exception, you are granted the permissions to link this     |
 | program with every library, which license fulfills the Open Source       |
 | Definition as published by the Open Source Initiative (OSI).             |
 *--------------------------------------------------------------------------*/

package org.rapla.client.swing;

import javax.swing.JComponent;

import org.rapla.client.RaplaWidget;
import org.rapla.inject.ExtensionPoint;
import org.rapla.inject.InjectionContext;
import org.rapla.scheduler.Promise;

@ExtensionPoint(context = InjectionContext.swing, id=SwingCalendarView.ID)
public interface SwingCalendarView<T> extends RaplaWidget<T>
{
    String ID = "org.rapla.client.swing.calendarview";
    Promise<Void> update();
    /** you can provide a DateSelection component if you want */
    JComponent getDateSelection();
    /** Most times you can only scroll programaticaly if the window is visible and the size of
     * the component is known, so this method gets called when the window is visible.
     * */
    void scrollToStart();
}
