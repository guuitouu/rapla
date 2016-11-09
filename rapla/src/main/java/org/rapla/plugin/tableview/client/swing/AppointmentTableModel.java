package org.rapla.plugin.tableview.client.swing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;

import org.rapla.components.xmlbundle.I18nBundle;
import org.rapla.entities.domain.AppointmentBlock;
import org.rapla.plugin.tableview.RaplaTableColumn;

public class AppointmentTableModel extends DefaultTableModel
{
    private static final long serialVersionUID = 1L;
    
    List<AppointmentBlock> appointments= new ArrayList<AppointmentBlock>();
    Locale locale;
    I18nBundle i18n;
    Map<Integer,RaplaTableColumn<AppointmentBlock,TableColumn>> columns = new LinkedHashMap<Integer, RaplaTableColumn<AppointmentBlock,TableColumn>>();
    
    //String[] columns;
    public AppointmentTableModel(Locale locale, I18nBundle i18n, Collection<RaplaTableColumn<AppointmentBlock,TableColumn>> columnPlugins) {
        this.locale = locale;
        this.i18n = i18n;
        List<String> columnNames = new ArrayList<String>(); 
        int column = 0;
        for (RaplaTableColumn<AppointmentBlock,TableColumn> col: columnPlugins)
        {
        	columnNames.add( col.getColumnName());
        	columns.put( column, col);
        	column++;
        }
        this.setColumnIdentifiers( columnNames.toArray());
    }

    public void setAppointments(List<AppointmentBlock> appointments2) {
        this.appointments = appointments2;
        super.fireTableDataChanged();
    }

    public AppointmentBlock getAppointmentAt(int row) {
        return this.appointments.get(row);
    }

    public boolean isCellEditable(int row, int column) {
        return false;
    }

    public int getRowCount() {
        if ( appointments != null)
            return appointments.size();
        else
            return 0;
    }

    public Object getValueAt( int rowIndex, int columnIndex )
    {
        AppointmentBlock event = getAppointmentAt(rowIndex);
        RaplaTableColumn<AppointmentBlock,TableColumn> tableColumn = columns.get( columnIndex);
        return tableColumn.getValue(event);
    }

    public Class<?> getColumnClass(int columnIndex) {
        RaplaTableColumn<AppointmentBlock,TableColumn> tableColumn = columns.get( columnIndex);
        return tableColumn.getColumnClass();
    }



}
